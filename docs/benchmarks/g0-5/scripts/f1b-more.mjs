import net from "node:net";
const port = 6379;
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
const val="C".repeat(20*1024*1024);
for(let i=2;i<=8;i++){await call("SET","big:"+i,val);}
await new Promise(r=>setTimeout(r,4000));
const info=text(await call("INFO"));
console.log(JSON.stringify({mem:m(info,"tiering_memtable_used_bytes"),coldTables:m(info,"tiering_cold_sstable_count"),coldBytes:m(info,"tiering_cold_disk_usage_bytes"),coldEntries:m(info,"tiering_cold_entries"),migOk:m(info,"tiering_migration_success"),migFail:m(info,"tiering_migration_failed"),flush:m(info,"tiering_flush_count"),dbsize:Number(text(await call("DBSIZE")))}));
s.end();
