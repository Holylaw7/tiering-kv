import net from "node:net"; import fs from "node:fs";
const port = Number(process.argv[2] || 6379);
const label = process.argv[3] || "f1b";
function parse(b){if(!b.length)return null;const nl=b.indexOf("\r\n");if(nl<0)return null;const h=b.subarray(0,nl).toString();const t=h[0];
 if(t==="+"||t==="-"||t===":")return{used:nl+2,value:h.slice(1),error:t==="-"}
 if(t==="$"){const len=Number(h.slice(1));if(len<0)return{used:nl+2,value:null};if(b.length<nl+2+len+2)return null;return{used:nl+2+len+2,value:b.subarray(nl+2,nl+2+len)};}
 if(t==="*"){let u=nl+2;const it=[];for(let i=0;i<Number(h.slice(1));i++){const x=parse(b.subarray(u));if(!x)return null;u+=x.used;it.push(x.value);}return{used:u,value:it};}
 return null;}
const socket=await new Promise((res,rej)=>{const s=net.connect(port,"127.0.0.1");s.on("connect",()=>res(s));s.on("error",rej);});
let buf=Buffer.alloc(0);const waiters=[];
socket.on("data",c=>{buf=Buffer.concat([buf,c]);while(waiters.length){const p=parse(buf);if(!p)break;buf=buf.subarray(p.used);waiters.shift()(p);}});
const next=()=>new Promise(r=>waiters.push(r));
const enc=c=>"*"+c.length+"\r\n"+c.map(x=>"$"+Buffer.byteLength(x)+"\r\n"+x+"\r\n").join("");
async function call(...c){socket.write(enc(c));return next();}
const text=r=>r.value===null?"":r.value.toString();
const metric=(i,k)=>{const m=i.match(new RegExp(k+":([0-9.]+)"));return m?Number(m[1]):-1;};
const snap=async()=>{const i=text(await call("INFO"));return{mem:metric(i,"tiering_memtable_used_bytes"),max:metric(i,"tiering_memtable_max_bytes"),flush:metric(i,"tiering_flush_count"),coldTables:metric(i,"tiering_cold_sstable_count"),coldBytes:metric(i,"tiering_cold_disk_usage_bytes"),coldEntries:metric(i,"tiering_cold_entries"),migOk:metric(i,"tiering_migration_success"),migFail:metric(i,"tiering_migration_failed"),migPending:metric(i,"tiering_migration_pending")};};

const small="a".repeat(200);
let written=0;
while (true) {
  const s = await snap();
  if (s.mem > 48 * 1024 * 1024) break;
  const batch=[];for(let i=0;i<2000;i++)batch.push(call("SET","base:"+(written+i),small));
  await Promise.all(batch); written += 2000;
}
const before = await snap();
const target = 64 * 1024 * 1024 + 8 * 1024 * 1024; // 超过 maxBytes 8MB
const bigSize = Math.max(4 * 1024 * 1024, target - before.mem);
const big = "B".repeat(bigSize);
const t0 = Date.now();
await call("SET", "big:one", big);
const writeMs = Date.now() - t0;
const samples=[];
for (let i = 0; i < 20; i++) { const s = await snap(); samples.push({ t: i, ...s }); await new Promise(r=>setTimeout(r,300)); }
const bigLen = text(await call("GET","big:one")).length;
const dbsize = Number(text(await call("DBSIZE")));
const out={label,port,baseKeys:written,before,bigSize,writeMs,samples,dbsize,bigReadLen:bigLen,at:new Date().toISOString()};
fs.mkdirSync("docs/benchmarks/g0-5/raw/f1",{recursive:true});
fs.writeFileSync("docs/benchmarks/g0-5/raw/f1/"+label+".json",JSON.stringify(out,null,2));
console.log(JSON.stringify({before:out.before,bigSize,writeMs,dbsize,bigReadLen:bigLen,last:samples[samples.length-1]}));
socket.end();
