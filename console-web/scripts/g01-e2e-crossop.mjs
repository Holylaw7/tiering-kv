
const A='http://127.0.0.1:8080', B='http://127.0.0.1:8081';
const login = async (b) => (await (await fetch(b+'/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'admin',password:'admin123'})})).json()).data.token;
const tA = await login(A), tB = await login(B);
const id = 37;
const p = fetch(A+'/api/v1/backups/'+id+'/restore',{method:'POST',headers:{Authorization:'Bearer '+tA}});
await new Promise(r=>setTimeout(r,300));
const exp = await fetch(B+'/api/v1/backups?instanceId=1',{method:'POST',headers:{Authorization:'Bearer '+tB}});
const expBody = await exp.json().catch(()=>null);
const rA = await p;
console.log('restore-on-A=' + rA.status, 'export-on-B=' + exp.status, JSON.stringify(expBody && expBody.message));
