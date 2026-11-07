
import net from 'node:net';
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
const A='http://127.0.0.1:8080', B='http://127.0.0.1:8081';
const MYSQL='E:\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe';
const DIR='E:\\学习\\Mini-redis项目\\tiering-kv\\console-server\\backups';
const sql=(q)=>execFileSync(MYSQL,['-h','127.0.0.1','-P','3307','--protocol=TCP','-u','root','tiering_console','-N','-e',q],{encoding:'utf8'}).trim();
const out=[]; const note=(k,v)=>{out.push({k,v});console.log(k,JSON.stringify(v));};
const login=async(b)=>(await(await fetch(b+'/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'admin',password:'admin123'})})).json()).data.token;
const api=async(b,t,p,i={})=>{const r=await fetch(b+p,{...i,headers:{Authorization:'Bearer '+t,'Content-Type':'application/json'}});let body=null;try{body=await r.json();}catch{};return{status:r.status,body};};
const resp=(port,cmds)=>new Promise((res,rej)=>{const s=net.connect(port,'127.0.0.1');let buf='';s.on('data',d=>buf+=d.toString());s.on('error',rej);s.on('connect',()=>{const enc=c=>'*'+c.length+'\r\n'+c.map(x=>'$'+Buffer.byteLength(x)+'\r\n'+x+'\r\n').join('');s.write(cmds.map(enc).join(''));setTimeout(()=>{s.end();res(buf);},400);});});

const token=await login(A);
await resp(6379,Array.from({length:50},(_,i)=>['SET','g02:k'+i,'v'+i]));

async function makeBackups(n){
  const ids=[];
  for(let i=0;i<n;i++){
    const c=await api(A,token,'/api/v1/backups?instanceId=1',{method:'POST'});
    const id=c.body.data.id; ids.push(id);
    for(let j=0;j<40;j++){const l=await api(A,token,'/api/v1/backups?instanceId=1');const r=l.body.data.find(x=>x.id===id);if(r&&r.status!=='RUNNING')break;await new Promise(x=>setTimeout(x,200));}
    await new Promise(x=>setTimeout(x,150));
  }
  return ids;
}
const ids=await makeBackups(4);
note('backups', ids);
note('rows-before', sql('SELECT COUNT(*) FROM backup_record WHERE instance_id=1 AND status=\'SUCCESS\''));
const filesBefore=fs.readdirSync(DIR).length;

const view=await api(A,token,'/api/v1/backups/retention');
note('retention-view', { http:view.status, count:view.body?.data?.defaultCount, lastReport:view.body?.data?.lastReport });
const purgeA=await api(A,token,'/api/v1/backups/purge',{method:'POST'});
note('purge', { http:purgeA.status, report:purgeA.body?.data });
note('rows-after', sql('SELECT COUNT(*) FROM backup_record WHERE instance_id=1 AND status=\'SUCCESS\''));
const filesAfter=fs.readdirSync(DIR).length;

// 保护规则：把一条成功记录标为 RESTORING，再清理不应删它
const keepIds=sql('SELECT id FROM backup_record WHERE instance_id=1 AND status=\'SUCCESS\' ORDER BY created_at DESC').split('\n');
const protectedId=Number(keepIds[0]);
sql('UPDATE backup_record SET restore_status=\'RESTORING\', restore_started_at=NOW(3) WHERE id='+protectedId);
// 造 3 个更老的备份，制造"可清理"候选
const extra=await makeBackups(3);
const purgeB=await api(A,token,'/api/v1/backups/purge',{method:'POST'});
note('purge-with-protected', { http:purgeB.status, deleted:purgeB.body?.data?.deleted, protectedStillThere: sql('SELECT COUNT(*) FROM backup_record WHERE id='+protectedId)==='1' });
sql('UPDATE backup_record SET restore_status=\'NONE\' WHERE id='+protectedId);

console.log('\n===SUMMARY===');
console.log(JSON.stringify({filesBefore,filesAfter,out},null,1));