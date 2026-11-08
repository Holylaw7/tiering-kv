import net from "node:net";
import fs from "node:fs";
const args = {}; const argv = process.argv.slice(2);
for (let i = 0; i < argv.length; i += 2) args[argv[i].replace(/^--/, "")] = argv[i + 1];
const port = Number(args.port || 6379);
const keys = Number(args.keys || 100000);
const valueSize = Number(args.value || 800);
const label = args.label || "construct";
function parse(buf) {
  if (!buf.length) return null;
  const nl = buf.indexOf("\r\n"); if (nl < 0) return null;
  const head = buf.subarray(0, nl).toString(); const type = head[0];
  if (type === "+" || type === "-" || type === ":") return { used: nl + 2, value: head.slice(1), error: type === "-" };
  if (type === "$") { const len = Number(head.slice(1)); if (len < 0) return { used: nl + 2, value: null }; if (buf.length < nl + 2 + len + 2) return null; return { used: nl + 2 + len + 2, value: buf.subarray(nl + 2, nl + 2 + len) }; }
  if (type === "*") { let used = nl + 2; const items = []; for (let i = 0; i < Number(head.slice(1)); i++) { const item = parse(buf.subarray(used)); if (!item) return null; used += item.used; items.push(item.value); } return { used, value: items }; }
  return null;
}
const socket = await new Promise((res, rej) => { const s = net.connect(port, "127.0.0.1"); s.on("connect", () => res(s)); s.on("error", rej); });
let buffer = Buffer.alloc(0); const waiters = [];
socket.on("data", (c) => { buffer = Buffer.concat([buffer, c]); while (waiters.length) { const p = parse(buffer); if (!p) break; buffer = buffer.subarray(p.used); waiters.shift()(p); } });
const next = () => new Promise((r) => waiters.push(r));
const enc = (c) => "*" + c.length + "\r\n" + c.map((x) => "$" + Buffer.byteLength(x) + "\r\n" + x + "\r\n").join("");
async function call(...c) { socket.write(enc(c)); return next(); }
const text = (r) => r.value === null ? "" : r.value.toString();
const metric = (info, k) => { const m = info.match(new RegExp(k + ":([0-9.]+)")); return m ? Number(m[1]) : -1; };

const timeline = [];
const value = "y".repeat(valueSize);
const t0 = Date.now();
for (let start = 0; start < keys; start += 1000) {
  const batch = [];
  for (let i = start; i < Math.min(start + 1000, keys); i++) batch.push(call("SET", "cold:" + i, value));
  await Promise.all(batch);
  if ((start / 1000) % 10 === 0) {
    const info = text(await call("INFO"));
    timeline.push({ sec: Math.round((Date.now() - t0) / 1000), written: start + 1000,
      mem: metric(info, "tiering_memtable_used_bytes"), coldTables: metric(info, "tiering_cold_sstable_count"),
      coldBytes: metric(info, "tiering_cold_disk_usage_bytes"), migPending: metric(info, "tiering_migration_pending"),
      migOk: metric(info, "tiering_migration_success"), migFail: metric(info, "tiering_migration_failed"),
      flush: metric(info, "tiering_flush_count") });
  }
}
await new Promise((r) => setTimeout(r, 5000));
const info = text(await call("INFO"));
const dbsize = Number(text(await call("DBSIZE")));
let ok = 0;
for (let i = 0; i < 200; i++) { const v = text(await call("GET", "cold:" + (i * 499) % keys)); if (v.length === valueSize) ok++; }
const out = { label, port, keys, valueSize, seedMs: Date.now() - t0, dbsize, readBackOk: ok,
  final: { mem: metric(info, "tiering_memtable_used_bytes"), coldTables: metric(info, "tiering_cold_sstable_count"),
    coldBytes: metric(info, "tiering_cold_disk_usage_bytes"), migPending: metric(info, "tiering_migration_pending"),
    migOk: metric(info, "tiering_migration_success"), migFail: metric(info, "tiering_migration_failed"),
    flush: metric(info, "tiering_flush_count"), coldEntries: metric(info, "tiering_cold_entries") },
  timeline, at: new Date().toISOString() };
fs.mkdirSync("docs/benchmarks/g0-5/raw/f1", { recursive: true });
fs.writeFileSync("docs/benchmarks/g0-5/raw/f1/" + label + ".json", JSON.stringify(out, null, 2));
console.log(JSON.stringify({ label, dbsize, readBackOk: ok, final: out.final }));
socket.end();
