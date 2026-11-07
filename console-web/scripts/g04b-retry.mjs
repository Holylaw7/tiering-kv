
const A='http://127.0.0.1:8080', B='http://127.0.0.1:8081';
const login=async(b)=>(await(await fetch(b+'/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'admin',password:'admin123'})})).json()).data.token;
const api=async(b,t,p,i={})=>{const r=await fetch(b+p,{...i,headers:{Authorization:'Bearer '+t,'Content-Type':'application/json'}});let x=null;try{x=await r.json();}catch{};return{status:r.status,body:x};};
const tB=await login(B);
const retry=await api(B,tB,'/api/v1/backups?instanceId=2',{method:'POST'});
console.log('B retry http='+retry.status, JSON.stringify(retry.body?.data?{id:retry.body.data.id,status:retry.body.data.status}:retry.body).slice(0,200));
