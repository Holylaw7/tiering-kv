
import net from 'node:net';

const A = 'http://127.0.0.1:8080', B = 'http://127.0.0.1:8081';
const log = [];
function note(k, v) { log.push({ t: new Date().toISOString(), k, v }); console.log(k, JSON.stringify(v)); }
async function login(base) {
  const r = await fetch(base + '/api/v1/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' }) });
  const j = await r.json();
  if (!j.data || !j.data.token) throw new Error('login failed ' + JSON.stringify(j));
  return j.data.token;
}
async function api(base, token, path, init = {}) {
  const r = await fetch(base + path, { ...init, headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json', ...(init.headers || {}) } });
  let body = null; try { body = await r.json(); } catch {}
  return { status: r.status, body };
}
function resp(port, cmds) {
  return new Promise((resolve, reject) => {
    const s = net.connect(port, '127.0.0.1');
    let buf = ''; s.on('data', d => { buf += d.toString(); }); s.on('error', reject);
    s.on('connect', () => {
      const enc = c => '*' + c.length + '\r\n' + c.map(x => '$' + Buffer.byteLength(x) + '\r\n' + x + '\r\n').join('');
      s.write(cmds.map(enc).join(''));
      setTimeout(() => { s.end(); resolve(buf); }, 500);
    });
  });
}
(async () => {
  const tokenA = await login(A), tokenB = await login(B);
  note('login', { A: !!tokenA, B: !!tokenB });

  // 1) 实例可用性
  const inst = await api(A, tokenA, '/api/v1/instances');
  note('instances', (inst.body.data || []).map(i => ({ id: i.id, name: i.name, status: i.status, version: i.version })));

  // 2) 造 20000 键
  const batches = [];
  for (let start = 0; start < 20000; start += 1000) {
    const cmds = [];
    for (let i = start; i < start + 1000; i++) cmds.push(['SET', 'g01:key:' + i, 'v' + i]);
    batches.push(resp(6379, cmds));
  }
  await Promise.all(batches);
  const size = await resp(6379, [['DBSIZE']]);
  note('seeded', size.trim());

  // 3) 建备份并等待 SUCCESS
  const created = await api(A, tokenA, '/api/v1/backups?instanceId=1', { method: 'POST' });
  note('backup-create', { status: created.status, id: created.body?.data?.id, state: created.body?.data?.status });
  const id = created.body.data.id;
  let rec = null;
  for (let i = 0; i < 60; i++) {
    const list = await api(A, tokenA, '/api/v1/backups?instanceId=1');
    rec = (list.body.data || []).find(x => x.id === id);
    if (rec && rec.status !== 'RUNNING') break;
    await new Promise(r => setTimeout(r, 500));
  }
  note('backup-done', { id, status: rec.status, keyCount: rec.keyCount, sizeBytes: rec.sizeBytes });

  // 4) 两个实例同时恢复
  const t0 = Date.now();
  const pA = api(A, tokenA, '/api/v1/backups/' + id + '/restore', { method: 'POST' });
  const pB = api(B, tokenB, '/api/v1/backups/' + id + '/restore', { method: 'POST' });
  const [rA, rB] = await Promise.all([pA, pB]);
  note('restore-race', { A: rA.status, B: rB.status, bodies: [rA.body?.message, rB.body?.message], ms: Date.now() - t0 });

  // 5) 状态与数据核对
  const list2 = await api(A, tokenA, '/api/v1/backups?instanceId=1');
  const after = (list2.body.data || []).find(x => x.id === id);
  note('record-after', { restoreStatus: after.restoreStatus, restoredKeys: after.restoredKeys, status: after.status, message: after.restoreMessage });
  const size2 = await resp(6379, [['DBSIZE']]);
  const sample = await resp(6379, [['GET', 'g01:key:19999']]);
  note('data-check', { dbsize: size2.trim(), sample: sample.trim() });

  console.log('\n===TIMELINE===');
  console.log(JSON.stringify(log, null, 1));
})().catch(e => { console.error('FATAL', e); process.exit(1); });