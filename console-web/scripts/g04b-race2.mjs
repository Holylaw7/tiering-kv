
const A='http://127.0.0.1:8080', B='http://127.0.0.1:8081';
const login=async(b)=>(await(await fetch(b+'/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'admin',password:'admin123'})})).json()).data.token;
const api=async(b,t,p,i={})=>{const r=await fetch(b+p,{...i,headers:{Authorization:'Bearer '+t,'Content-Type':'application/json'}});let x=null;try{x=await r.json();}catch{};return{status:r.status,body:x};};
const tA=await login(A), tB=await login(B);
const cap=await api(A,tA,'/api/v1/backups/capacity');
console.log('capacity', JSON.stringify(cap.body?.data));
const t0=Date.now();
const [r1,r2]=await Promise.all([
  api(A,tA,'/api/v1/backups?instanceId=1',{method:'POST'}),
  api(B,tB,'/api/v1/backups?instanceId=2',{method:'POST'})
]);
console.log('RACE ms='+(Date.now()-t0));
console.log('A http='+r1.status, JSON.stringify(r1.body?.data?{id:r1.body.data.id,status:r1.body.data.status}:r1.body).slice(0,220));
console.log('B http='+r2.status, JSON.stringify(r2.body).slice(0,260));
