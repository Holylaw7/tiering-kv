// G0-5C-1 复测脚本：GET/SET 分开采样，预热独立，输出原始 JSON
import net from "node:net";
import fs from "node:fs";

const args = {}; const argv = process.argv.slice(2);
for (let i = 0; i < argv.length; i += 2) args[argv[i].replace(/^--/, "")] = argv[i + 1];
const port = Number(args.port || 6379);
const keys = Number(args.keys || 20000);
const valueSize = Number(args.value || 100);
const gets = Number(args.gets || 5000);
const sets = Number(args.sets || 5000);
const infos = Number(args.infos || 300);
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
const summarize = (a) => { const s = [...a].sort((x, y) => x - y); return { n: s.length, p50: pct(s, 0.5), p95: pct(s, 0.95), p99: pct(s, 0.99), max: s[s.length - 1], avg: s.reduce((x, y) => x + y, 0) / s.length }; };

const socket = await new Promise((res, rej) => { const s = net.connect(port, "127.0.0.1"); s.on("connect", () => res(s)); s.on("error", rej); });
let buffer = Buffer.alloc(0); const waiters = [];
socket.on("data", (c) => { buffer = Buffer.concat([buffer, c]); while (waiters.length) { const p = parse(buffer); if (!p) break; buffer = buffer.subarray(p.used); waiters.shift()(p); } });
const next = () => new Promise((r) => waiters.push(r));
const enc = (c) => "*" + c.length + "\r\n" + c.map((x) => "$" + Buffer.byteLength(x) + "\r\n" + x + "\r\n").join("");
let errors = 0;
async function call(...c) { socket.write(enc(c)); const r = await next(); if (r.error) errors++; return r; }

const value = "v".repeat(valueSize);
for (let start = 0; start < keys; start += 2000) { const b = []; for (let i = start; i < Math.min(start + 2000, keys); i++) b.push(call("SET", "b:" + i, value)); await Promise.all(b); }
for (let round = 0; round < 3; round++) for (let i = 0; i < 2000; i++) await call("GET", "b:" + ((round * 2000 + i) % keys));
for (let i = 0; i < 100; i++) await call("INFO");

const infoLat = []; for (let i = 0; i < infos; i++) { const t = process.hrtime.bigint(); await call("INFO"); infoLat.push(Number(process.hrtime.bigint() - t) / 1e6); }
const getLat = []; for (let i = 0; i < gets; i++) { const t = process.hrtime.bigint(); await call("GET", "b:" + (i % keys)); getLat.push(Number(process.hrtime.bigint() - t) / 1e6); }
const setLat = []; for (let i = 0; i < sets; i++) { const t = process.hrtime.bigint(); await call("SET", "b:" + (i % keys), value); setLat.push(Number(process.hrtime.bigint() - t) / 1e6); }

const result = { label, port, keys, valueSize, errors, info: summarize(infoLat), get: summarize(getLat), set: summarize(setLat), at: new Date().toISOString() };
fs.mkdirSync("docs/benchmarks/g0-5/raw", { recursive: true });
fs.writeFileSync("docs/benchmarks/g0-5/raw/" + label + ".json", JSON.stringify(result, null, 2));
console.log(JSON.stringify({ label, info: result.info.p50, get: result.get.p50, set: result.set.p50, errors }));
socket.end();
