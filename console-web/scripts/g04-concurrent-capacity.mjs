
const A='http://127.0.0.1:8080', B='http://127.0.0.1:8081';
const login=async(b)=>(await(await fetch(b+'/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'admin',password:'admin123'})})).json()).data.token;
const api=async(b,t,p,i={})=>{const r=await fetch(b+p,{...i,headers:{Authorization:'Bearer '+t,'Content-Type':'application/json'}});let x=null;try{x=await r.json();}catch{};return{status:r.status,body:x};};
const tA=await login(A), tB=await login(B);
// 等待上一轮备份结束
for(let i=0;i<60;i++){const l=await api(A,tA,'/api/v1/backups?instanceId=1');const r=l.body.data.find(x=>x.id===100);if(!r||r.status!=='RUNNING')break;await new Promise(x=>setTimeout(x,300));}
const inst=await api(A,tA,'/api/v1/instances',{method:'POST',body:JSON.stringify({name:'cap-test-'+Date.now(),host:'127.0.0.1',port:6379})});
console.log('create instance http='+inst.status, JSON.stringify(inst.body?.data?{id:inst.body.data.id,name:inst.body.data.name}:inst.body));
const id2=inst.body?.data?.id;
const [r1,r2]=await Promise.all([
  api(A,tA,'/api/v1/backups?instanceId=1',{method:'POST'}),
  api(B,tB,'/api/v1/backups?instanceId='+id2,{method:'POST'})
]);
console.log('A http='+r1.status, JSON.stringify(r1.body?.data?{id:r1.body.data.id,status:r1.body.data.status}:r1.body));
console.log('B http='+r2.status, JSON.stringify(r2.body?.data?{id:r2.body.data.id,status:r2.body.data.status}:r2.body));
