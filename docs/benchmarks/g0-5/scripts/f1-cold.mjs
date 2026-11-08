// G0-5C-2 F1 冷层场景：构造 + 资格检查 + INFO/DBSIZE 采样
import net from "node:net";
import fs from "node:fs";

const args = {}; const argv = process.argv.slice(2);
for (let i = 0; i < argv.length; i += 2) args[argv[i].replace(/^--/, "")] = argv[i + 1];
const port = Number(args.port || 6379);
const keys = Number(args.keys || 100000);
const rounds = Number(args.rounds || 3);
const perRound = Number(args.perRound || 200);
const label = args.label || "run";

function parse(buf) {
  if (!buf.length) return null;
  const nl = buf.indexOf("\r\n"); if (nl < 0) return null;
  const head = buf.subarray(0, nl).toString(); const type = head[0];
  if (type === "+" || type === "-" || type === ":") return { used: nl + 2, value: head.slice(1), error: type === "-" };
  if (type === "$") { const len = Number(head.slice(1)); if (len < 0) return { used: nl + 2, value: null }; if (buf.length < nl + 2 + len + 2) return null; return { used: nl + 2 + len + 2, value: buf.subarray(nl + 2, nl + 2 + len) }; }
  if (type === "*") { let used = nl + 2; const items = []; for (let i = 0; i < Number(head.slice(1)); i++) { const item = parse(buf.subarray(used)); if (!item) return null; used += item.used; items.push(item.value); } return { used, value: items }; }
  return null;
}
const pct = (s, p) => s[Math.min(s.length - 1, Math.floor(p * s.length))];
const summarize = (a) => { const s = [...a].sort((x, y) => x - y); return { n: s.length, p50: pct(s, 0.5), p95: pct(s, 0.95), p99: pct(s, 0.99), max: s[s.length - 1] }; };

const socket = await new Promise((res, rej) => { const s = net.connect(port, "127.0.0.1"); s.on("connect", () => res(s)); s.on("error", rej); });
let buffer = Buffer.alloc(0); const waiters = [];
socket.on("data", (c) => { buffer = Buffer.concat([buffer, c]); while (waiters.length) { const p = parse(buffer); if (!p) break; buffer = buffer.subarray(p.used); waiters.shift()(p); } });
const next = () => new Promise((r) => waiters.push(r));
const enc = (c) => "*" + c.length + "\r\n" + c.map((x) => "$" + Buffer.byteLength(x) + "\r\n" + x + "\r\n").join("");
let errors = 0;
async function call(...c) { socket.write(enc(c)); const r = await next(); if (r.error) errors++; return r; }
const text = (r) => r.value === null ? "" : r.value.toString();

const tSeed0 = Date.now();
const value = "x".repeat(40);
for (let start = 0; start < keys; start += 2000) {
  const batch = [];
  for (let i = start; i < Math.min(start + 2000, keys); i++) batch.push(call("SET", "c:" + i, value));
  await Promise.all(batch);
}
const seedMs = Date.now() - tSeed0;

// 等待 flush / 迁移稳定
let cold = 0; let flushCount = 0; let stable = 0;
for (let i = 0; i < 120; i++) {
  const info = text(await call("INFO"));
  const g = (k) => { const m = info.match(new RegExp(k + ":([0-9]+)")); return m ? Number(m[1]) : -1; };
  const c = g("tiering_cold_sstable_count"); const f = g("tiering_flush_count");
  if (c === cold && f === flushCount) stable++; else stable = 0;
  cold = c; flushCount = f;
  if (cold > 0 && stable >= 3) break;
  await new Promise((r) => setTimeout(r, 500));
}
const dbsize = Number(text(await call("DBSIZE")));
const infoAfter = text(await call("INFO"));
const metric = (k) => { const m = infoAfter.match(new RegExp(k + ":([0-9.]+)")); return m ? Number(m[1]) : -1; };

const firstInfo = []; const firstDb = [];
{ const t = process.hrtime.bigint(); await call("INFO"); firstInfo.push(Number(process.hrtime.bigint() - t) / 1e6); }
{ const t = process.hrtime.bigint(); await call("DBSIZE"); firstDb.push(Number(process.hrtime.bigint() - t) / 1e6); }

const results = [];
for (let r = 0; r < rounds; r++) {
  const infoLat = []; const dbLat = [];
  for (let i = 0; i < perRound; i++) { const t = process.hrtime.bigint(); await call("INFO"); infoLat.push(Number(process.hrtime.bigint() - t) / 1e6); }
  for (let i = 0; i < perRound; i++) { const t = process.hrtime.bigint(); await call("DBSIZE"); dbLat.push(Number(process.hrtime.bigint() - t) / 1e6); }
  results.push({ round: r + 1, info: summarize(infoLat), dbsize: summarize(dbLat) });
}

const out = {
  label, port, keys, seedMs,
  qualified: { coldSstableCount: cold, flushCount, dbsize, logicalKeysExpected: keys, ok: cold > 0 && dbsize === keys },
  metrics: { coldDiskBytes: metric("tiering_cold_disk_usage_bytes"), coldEntries: metric("tiering_cold_entries"), memEntries: metric("tiering_memtable_entries") },
  first: { info: firstInfo[0], dbsize: firstDb[0] },
  rounds: results, errors, at: new Date().toISOString(),
};
fs.mkdirSync("docs/benchmarks/g0-5/raw/f1", { recursive: true });
fs.writeFileSync("docs/benchmarks/g0-5/raw/f1/" + label + ".json", JSON.stringify(out, null, 2));
console.log(JSON.stringify({ label, qualified: out.qualified, coldDiskBytes: out.metrics.coldDiskBytes, rounds: results.map((x) => ({ info: x.info.p50, db: x.dbsize.p50 })) }));
socket.end();
