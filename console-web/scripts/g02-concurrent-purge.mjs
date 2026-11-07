
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
const A='http://127.0.0.1:8080', B='http://127.0.0.1:8081';
const MYSQL='E:\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe';
const DIR='E:\\学习\\Mini-redis项目\\tiering-kv\\console-server\\backups';
const sql=(q)=>execFileSync(MYSQL,['-h','127.0.0.1','-P','3307','--protocol=TCP','-u','root','tiering_console','-N','-e',q],{encoding:'utf8'}).trim();
const login=async(b)=>(await(await fetch(b+'/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'admin',password:'admin123'})})).json()).data.token;
const api=async(b,t,p,i={})=>{const r=await fetch(b+p,{...i,headers:{Authorization:'Bearer '+t,'Content-Type':'application/json'}});let x=null;try{x=await r.json();}catch{};return{status:r.status,body:x};};

const tA=await login(A), tB=await login(B);
const mk=async(n)=>{const ids=[];for(let i=0;i<n;i++){const c=await api(A,tA,'/api/v1/backups?instanceId=1',{method:'POST'});ids.push(c.body.data.id);for(let j=0;j<40;j++){const l=await api(A,tA,'/api/v1/backups?instanceId=1');const r=l.body.data.find(x=>x.id===c.body.data.id);if(r&&r.status!=='RUNNING')break;await new Promise(x=>setTimeout(x,150));}await new Promise(x=>setTimeout(x,120));}return ids;};

// 1) 孤儿文件（2 小时前修改）应被清理
const orphan=path.join(DIR,'orphan-g02.jsonl');
fs.writeFileSync(orphan,'x');
const old=new Date(Date.now()-2*3600*1000);
fs.utimesSync(orphan, old, old);
const p1=await api(A,tA,'/api/v1/backups/purge',{method:'POST'});
console.log('orphan purge: http='+p1.status+' removed='+p1.body?.data?.orphanRemoved+' exists='+fs.existsSync(orphan));

// 2) 两实例并发清理
const before=Number(sql('SELECT COUNT(*) FROM backup_record WHERE instance_id=1 AND status=\'SUCCESS\''));
const ids=await mk(4);
const [r1,r2]=await Promise.all([
  api(A,tA,'/api/v1/backups/purge',{method:'POST'}),
  api(B,tB,'/api/v1/backups/purge',{method:'POST'})
]);
const after=Number(sql('SELECT COUNT(*) FROM backup_record WHERE instance_id=1 AND status=\'SUCCESS\''));
console.log(JSON.stringify({before, created:ids, purgeA:{http:r1.status,deleted:r1.body?.data?.deleted,skipped:r1.body?.data?.skipped}, purgeB:{http:r2.status,deleted:r2.body?.data?.deleted,skipped:r2.body?.data?.skipped}, after}));
