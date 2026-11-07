
const A='http://127.0.0.1:8080';
const login=async(b)=>(await(await fetch(b+'/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'admin',password:'admin123'})})).json()).data.token;
const api=async(b,t,p,i={})=>{const r=await fetch(b+p,{...i,headers:{Authorization:'Bearer '+t,'Content-Type':'application/json'}});let x=null;try{x=await r.json();}catch{};return{status:r.status,body:x};};
const t=await login(A);
const cap=await api(A,t,'/api/v1/backups/capacity');
console.log('capacity', JSON.stringify(cap.body?.data));
const c=await api(A,t,'/api/v1/backups?instanceId=1',{method:'POST'});
console.log('create http='+c.status, JSON.stringify(c.body?.data?{id:c.body.data.id,status:c.body.data.status}:c.body));
if(c.body?.data?.id){
  for(let i=0;i<50;i++){const l=await api(A,t,'/api/v1/backups?instanceId=1');const r=l.body.data.find(x=>x.id===c.body.data.id);if(r&&r.status!=='RUNNING'){console.log('backup', JSON.stringify({status:r.status,keyCount:r.keyCount,sizeBytes:r.sizeBytes}));break;}await new Promise(x=>setTimeout(x,300));}
}
