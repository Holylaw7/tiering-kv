
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
const A = 'http://127.0.0.1:8080';
const MYSQL = 'E:\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe';
const BACKUP_DIR = 'E:\\学习\\Mini-redis项目\\tiering-kv\\console-server\\backups';
const out = [];
const note = (k, v) => { out.push({ k, v }); console.log(k, JSON.stringify(v)); };
const sql = (q) => execFileSync(MYSQL, ['-h', '127.0.0.1', '-P', '3307', '--protocol=TCP', '-u', 'root', 'tiering_console', '-N', '-e', q], { encoding: 'utf8' }).trim();

async function login() {
  const r = await fetch(A + '/api/v1/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'admin', password: 'admin123' }) });
  return (await r.json()).data.token;
}
async function api(t, path, init = {}) {
  const r = await fetch(A + path, { ...init, headers: { Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' } });
  let body = null; try { body = await r.json(); } catch {}
  return { status: r.status, body };
}

const token = await login();

// ---------- 故障场景：备份文件损坏 ----------
const id = 37;
const fileName = sql('SELECT file_name FROM backup_record WHERE id=' + id);
const file = BACKUP_DIR + '\\' + fileName;
const bak = file + '.bak';
fs.copyFileSync(file, bak);
const lines = fs.readFileSync(file, 'utf8').split('\n');
note('file', { fileName, lines: lines.length, bytes: fs.statSync(file).size });

// 在第 100 行之后插入一行非法 JSON，模拟"备份文件中途损坏"
const broken = lines.slice(0, 100).concat(['{损坏的行}']).concat(lines.slice(100));
fs.writeFileSync(file, broken.join('\n'));
const fail = await api(token, '/api/v1/backups/' + id + '/restore', { method: 'POST' });
note('restore-broken', { http: fail.status, message: (fail.body?.message || '').slice(0, 120) });
const rec1 = (await api(token, '/api/v1/backups?instanceId=1')).body.data.find(x => x.id === id);
note('record-after-failure', { restoreStatus: rec1.restoreStatus, restoredKeys: rec1.restoredKeys, backupStatus: rec1.status, message: rec1.restoreMessage });

// 修复文件后重试
fs.copyFileSync(bak, file);
const retry = await api(token, '/api/v1/backups/' + id + '/restore', { method: 'POST' });
note('restore-retry', { http: retry.status });
const rec2 = (await api(token, '/api/v1/backups?instanceId=1')).body.data.find(x => x.id === id);
note('record-after-retry', { restoreStatus: rec2.restoreStatus, restoredKeys: rec2.restoredKeys, backupStatus: rec2.status });
fs.unlinkSync(bak);

// ---------- 故障场景：僵尸 RESTORING ----------
const created = await api(token, '/api/v1/backups?instanceId=1', { method: 'POST' });
const newId = created.body.data.id;
for (let i = 0; i < 60; i++) {
  const r = (await api(token, '/api/v1/backups?instanceId=1')).body.data.find(x => x.id === newId);
  if (r && r.status !== 'RUNNING') break;
  await new Promise(r2 => setTimeout(r2, 500));
}
sql('UPDATE backup_record SET restore_status="RESTORING", restore_started_at=DATE_SUB(NOW(3), INTERVAL 45 MINUTE) WHERE id=' + newId);
note('zombie-planted', { id: newId, restoreStatus: sql('SELECT restore_status FROM backup_record WHERE id=' + newId) });

const zr = await api(token, '/api/v1/backups/' + id + '/restore', { method: 'POST' });
note('restore-with-zombie', { http: zr.status });
const zombie = sql('SELECT restore_status, restore_message FROM backup_record WHERE id=' + newId);
note('zombie-after', zombie);

console.log('\n===RESULT===');
console.log(JSON.stringify(out, null, 1));
