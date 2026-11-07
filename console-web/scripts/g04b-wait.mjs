
const A='http://127.0.0.1:8080';
const login=async(b)=>(await(await fetch(b+'/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'admin',password:'admin123'})})).json()).data.token;
const api=async(b,t,p,i={})=>{const r=await fetch(b+p,{...i,headers:{Authorization:'Bearer '+t,'Content-Type':'application/json'}});let x=null;try{x=await r.json();}catch{};return{status:r.status,body:x};};
const t=await login(A);
for(let i=0;i<60;i++){
  const l=await api(A,t,'/api/v1/backups?instanceId=1');
  const running=(l.body.data||[]).filter(x=>x.status==='RUNNING');
  const cap=await api(A,t,'/api/v1/backups/capacity');
  console.log('tick',i,'running='+running.length,'reserved='+cap.body?.data?.reservedBytes);
  if(running.length===0) break;
  await new Promise(x=>setTimeout(x,1000));
}
const l=await api(A,t,'/api/v1/backups?instanceId=1');
console.log('records', (l.body.data||[]).slice(0,4).map(x=>({id:x.id,status:x.status,keys:x.keyCount,size:x.sizeBytes})));
