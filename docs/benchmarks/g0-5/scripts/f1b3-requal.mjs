import net from "node:net"; import fs from "node:fs";
const port = Number(process.argv[2] || 6379); const label = process.argv[3] || "requal";
function parse(b){if(!b.length)return null;const nl=b.indexOf("\r\n");if(nl<0)return null;const h=b.subarray(0,nl).toString();const t=h[0];
 if(t==="+"||t==="-"||t===":")return{used:nl+2,value:h.slice(1)};
 if(t==="$"){const len=Number(h.slice(1));if(len<0)return{used:nl+2,value:null};if(b.length<nl+2+len+2)return null;return{used:nl+2+len+2,value:b.subarray(nl+2,nl+2+len)};}return null;}
const s=await new Promise((res,rej)=>{const x=net.connect(port,"127.0.0.1");x.on("connect",()=>res(x));x.on("error",rej);});
let buf=Buffer.alloc(0);const w=[];s.on("data",c=>{buf=Buffer.concat([buf,c]);while(w.length){const p=parse(buf);if(!p)break;buf=buf.subarray(p.used);w.shift()(p);}});
const next=()=>new Promise(r=>w.push(r));
const enc=c=>"*"+c.length+"\r\n"+c.map(x=>"$"+Buffer.byteLength(x)+"\r\n"+x+"\r\n").join("");
async function call(...c){s.write(enc(c));return next();}
const text=r=>r.value===null?"":r.value.toString();
const m=(i,k)=>{const x=i.match(new RegExp(k+":([0-9.]+)"));return x?Number(x[1]):-1;};
const snap=async()=>{const i=text(await call("INFO"));return{mem:m(i,"tiering_memtable_used_bytes"),flush:m(i,"tiering_flush_count"),migOk:m(i,"tiering_migration_success"),migFail:m(i,"tiering_migration_failed"),migPending:m(i,"tiering_migration_pending"),coldTables:m(i,"tiering_cold_sstable_count"),coldBytes:m(i,"tiering_cold_disk_usage_bytes"),coldEntries:m(i,"tiering_cold_entries"),pendEntries:m(i,"tiering_cold_pending_entries"),pendBytes:m(i,"tiering_cold_pending_bytes")};};

const small="a".repeat(200); let written=0;
while (true) { const t=await snap(); if (t.mem > 48*1024*1024) break;
  const b=[]; for(let i=0;i<2000;i++) b.push(call("SET","base:"+(written+i),small)); await Promise.all(b); written+=2000; }
const T0 = await snap();
const bigSize = 25*1024*1024; const big="B".repeat(bigSize);
const t1=Date.now(); await call("SET","big:one",big); const writeMs=Date.now()-t1;
const T1 = await snap();
const timeline=[];
for (const sec of [0,1,2,5,10,30]) {
  if (sec>0) await new Promise(r=>setTimeout(r, sec*1000 - (timeline.length?0:0)));
  const st=await snap(); timeline.push({at:sec,...st});
}
const bigLen = text(await call("GET","big:one")).length;
const dbsize = Number(text(await call("DBSIZE")));
const out={label,port,baseKeys:written,bigSize,writeMs,T0,T1,timeline,dbsize,bigReadLen:bigLen,at:new Date().toISOString()};
fs.mkdirSync("docs/benchmarks/g0-5/raw/f1",{recursive:true});
fs.writeFileSync("docs/benchmarks/g0-5/raw/f1/"+label+".json",JSON.stringify(out,null,2));
console.log(JSON.stringify({T0,T1,timeline:timeline.map(x=>({at:x.at,pendEntries:x.pendEntries,pendBytes:x.pendBytes,coldTables:x.coldTables,migOk:x.migOk,flush:x.flush})),dbsize,bigReadLen:bigLen}));
s.end();
