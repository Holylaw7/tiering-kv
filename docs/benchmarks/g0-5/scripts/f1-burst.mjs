import net from "node:net";
import fs from "node:fs";
const port = Number(process.argv[2] || 6379);
const total = Number(process.argv[3] || 200000);
const batchSize = Number(process.argv[4] || 20000);
const valueSize = 800;
const label = process.argv[5] || "burst";
function parse(buf) { if (!buf.length) return null; const nl = buf.indexOf("\r\n"); if (nl < 0) return null; const head = buf.subarray(0, nl).toString(); const t = head[0];
  if (t === "+" || t === "-" || t === ":") return { used: nl + 2, value: head.slice(1), error: t === "-" };
  if (t === "$") { const len = Number(head.slice(1)); if (len < 0) return { used: nl + 2, value: null }; if (buf.length < nl + 2 + len + 2) return null; return { used: nl + 2 + len + 2, value: buf.subarray(nl + 2, nl + 2 + len) }; }
  return null; }
const socket = await new Promise((res, rej) => { const s = net.connect(port, "127.0.0.1"); s.on("connect", () => res(s)); s.on("error", rej); });
let buffer = Buffer.alloc(0); const waiters = [];
socket.on("data", (c) => { buffer = Buffer.concat([buffer, c]); while (waiters.length) { const p = parse(buffer); if (!p) break; buffer = buffer.subarray(p.used); waiters.shift()(p); } });
const next = () => new Promise((r) => waiters.push(r));
const enc = (c) => "*" + c.length + "\r\n" + c.map((x) => "$" + Buffer.byteLength(x) + "\r\n" + x + "\r\n").join("");
async function call(...c) { socket.write(enc(c)); return next(); }
const text = (r) => r.value === null ? "" : r.value.toString();
const metric = (i, k) => { const m = i.match(new RegExp(k + ":([0-9.]+)")); return m ? Number(m[1]) : -1; };
const value = "z".repeat(valueSize);
const timeline = []; const t0 = Date.now();
for (let start = 0; start < total; start += batchSize) {
  const batch = [];
  for (let i = start; i < Math.min(start + batchSize, total); i++) batch.push(call("SET", "burst:" + i, value));
  await Promise.all(batch);
  const info = text(await call("INFO"));
  timeline.push({ t: Math.round((Date.now() - t0) / 1000), written: start + batch.length,
    mem: metric(info, "tiering_memtable_used_bytes"), coldTables: metric(info, "tiering_cold_sstable_count"),
    coldBytes: metric(info, "tiering_cold_disk_usage_bytes"), migPending: metric(info, "tiering_migration_pending"),
    migOk: metric(info, "tiering_migration_success"), migFail: metric(info, "tiering_migration_failed"),
    flush: metric(info, "tiering_flush_count") });
}
await new Promise((r) => setTimeout(r, 5000));
const info = text(await call("INFO"));
const out = { label, total, batchSize, seedMs: Date.now() - t0, dbsize: Number(text(await call("DBSIZE"))),
  final: { mem: metric(info, "tiering_memtable_used_bytes"), coldTables: metric(info, "tiering_cold_sstable_count"),
    coldBytes: metric(info, "tiering_cold_disk_usage_bytes"), coldEntries: metric(info, "tiering_cold_entries"),
    migOk: metric(info, "tiering_migration_success"), migFail: metric(info, "tiering_migration_failed"),
    migPending: metric(info, "tiering_migration_pending"), flush: metric(info, "tiering_flush_count") }, timeline, at: new Date().toISOString() };
fs.mkdirSync("docs/benchmarks/g0-5/raw/f1", { recursive: true });
fs.writeFileSync("docs/benchmarks/g0-5/raw/f1/" + label + ".json", JSON.stringify(out, null, 2));
console.log(JSON.stringify(out.final));
socket.end();
