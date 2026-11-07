import { execFileSync } from 'node:child_process';
const A='http://127.0.0.1:8080';
const MYSQL='E:\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe';
const sql=(q)=>execFileSync(MYSQL,['-h','127.0.0.1','-P','3307','--protocol=TCP','-u','root','tiering_console','-N','-e',q],{encoding:'utf8'}).trim();
const login=async()=>(await(await fetch(A+'/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'admin',password:'admin123'})})).json()).data.token;
const api=async(t,p,i={})=>{const r=await fetch(A+p,{...i,headers:{Authorization:'Bearer '+t,'Content-Type':'application/json'}});let b=null;try{b=await r.json();}catch{};return{status:r.status,body:b};};
async function mk(n,token){const ids=[];for(let i=0;i<n;i++){const c=await api(token,'/api/v1/backups?instanceId=1',{method:'POST'});if(!c.body||!c.body.data){console.log('create failed',c.status);return ids;}ids.push(c.body.data.id);for(let j=0;j<40;j++){const l=await api(token,'/api/v1/backups?instanceId=1');const r=l.body.data.find(x=>x.id===c.body.data.id);if(r&&r.status!=='RUNNING')break;await new Promise(x=>setTimeout(x,150));}await new Promise(x=>setTimeout(x,120));}return ids;}
sql("UPDATE backup_record SET restore_status='NONE' WHERE restore_status='RESTORING'");
const token=await login();
const first=await mk(2,token);
const second=await mk(3,token);
const target=first[0];
sql('UPDATE backup_record SET restore_status=\'RESTORING\', restore_started_at=NOW(3) WHERE id='+target);
const purge=await api(token,'/api/v1/backups/purge',{method:'POST'});
const row=sql('SELECT id,status,restore_status FROM backup_record WHERE id='+target);
console.log('protected id='+target);
console.log('purge deleted='+purge.body?.data?.deleted+' skipped='+purge.body?.data?.skipped);
console.log('protected row after purge: ['+row+']');
sql('UPDATE backup_record SET restore_status=\'NONE\' WHERE id='+target);