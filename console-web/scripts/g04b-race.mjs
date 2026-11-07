
import net from 'node:net';
const A='http://127.0.0.1:8080', B='http://127.0.0.1:8081';
const login=async(b)=>(await(await fetch(b+'/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'admin',password:'admin123'})})).json()).data.token;
const api=async(b,t,p,i={})=>{const r=await fetch(b+p,{...i,headers:{Authorization:'Bearer '+t,'Content-Type':'application/json'}});let x=null;try{x=await r.json();}catch{};return{status:r.status,body:x};};
const resp=(port,cmds)=>new Promise((res,rej)=>{const s=net.connect(port,'127.0.0.1');let buf='';s.on('data',d=>buf+=d.toString());s.on('error',rej);s.on('connect',()=>{const enc=c=>'*'+c.length+'\r\n'+c.map(x=>'$'+Buffer.byteLength(x)+'\r\n'+x+'\r\n').join('');s.write(cmds.map(enc).join(''));setTimeout(()=>{s.end();res(buf);},800);});});
const tA=await login(A), tB=await login(B);
const inst=await api(A,tA,'/api/v1/instances?name=k6383&host=127.0.0.1&port=6383',{method:'POST'});
let id2=null;
if(inst.status===200){ id2=inst.body.data.id; console.log('instance created id='+id2); }
else { const l=await api(A,tA,'/api/v1/instances'); const f=l.body.data.find(x=>x.host==='127.0.0.1'&&x.port===6383); id2=f&&f.id; console.log('instance existing id='+id2+' create='+inst.status); }
// seed 60k * 512B ≈ 30MB on kernel A
const val='z'.repeat(512);
for(let start=0;start<60000;start+=2000){
  const cmds=[]; for(let i=start;i<start+2000;i++) cmds.push(['SET','g04b:'+i,val]);
  await resp(6379,cmds);
}
const size=await resp(6379,[['DBSIZE']]);
console.log('seeded '+size.trim());
const cap=await api(A,tA,'/api/v1/backups/capacity');
console.log('capacity', JSON.stringify(cap.body?.data));
const t0=Date.now();
const [r1,r2]=await Promise.all([
  api(A,tA,'/api/v1/backups?instanceId=1',{method:'POST'}),
  api(B,tB,'/api/v1/backups?instanceId='+id2,{method:'POST'})
]);
console.log('RACE ms='+(Date.now()-t0));
console.log('A http='+r1.status, JSON.stringify(r1.body?.data?{id:r1.body.data.id,status:r1.body.data.status}:r1.body));
console.log('B http='+r2.status, JSON.stringify(r2.body?.data?{id:r2.body.data.id,status:r2.body.data.status}:r2.body));
