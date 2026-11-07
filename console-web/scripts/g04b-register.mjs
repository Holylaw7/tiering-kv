
const A='http://127.0.0.1:8080';
const login=async(b)=>(await(await fetch(b+'/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'admin',password:'admin123'})})).json()).data.token;
const api=async(b,t,p,i={})=>{const r=await fetch(b+p,{...i,headers:{Authorization:'Bearer '+t,'Content-Type':'application/json'}});let x=null;try{x=await r.json();}catch{};return{status:r.status,body:x};};
const t=await login(A);
const c=await api(A,t,'/api/v1/instances',{method:'POST',body:JSON.stringify({name:'k6383',host:'127.0.0.1',port:6383})});
console.log('create http='+c.status, JSON.stringify(c.body).slice(0,200));
const l=await api(A,t,'/api/v1/instances');
console.log('instances', (l.body.data||[]).map(x=>({id:x.id,host:x.host,port:x.port,status:x.status})));
