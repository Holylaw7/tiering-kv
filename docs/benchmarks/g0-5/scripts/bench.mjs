// G0-5C 统一基准脚本（Node，RESP over TCP）
// node bench.mjs --port 6379 --keys 20000 --value 100 --info 300 --ops 3000 --label after
import net from "node:net";
import fs from "node:fs";

const args = {};
const argv = process.argv.slice(2);
for (let i = 0; i < argv.length; i += 2) args[argv[i].replace(/^--/, "")] = argv[i + 1];
const port = Number(args.port || 6379);
const keys = Number(args.keys || 20000);
const valueSize = Number(args.value || 100);
const infoCount = Number(args.info || 300);
const ops = Number(args.ops || 3000);
const label = args.label || "unknown";

function parse(buf) {
  if (!buf.length) return null;
  const nl = buf.indexOf("\r\n");
  if (nl < 0) return null;
  const head = buf.subarray(0, nl).toString();
  const type = head[0];
  if (type === "+" || type === "-" || type === ":") return { used: nl + 2, value: head.slice(1) };
  if (type === "$") {
    const len = Number(head.slice(1));
    if (len < 0) return { used: nl + 2, value: null };
    if (buf.length < nl + 2 + len + 2) return null;
    return { used: nl + 2 + len + 2, value: buf.subarray(nl + 2, nl + 2 + len) };
  }
  if (type === "*") {
    let used = nl + 2;
    const items = [];
    for (let i = 0; i < Number(head.slice(1)); i++) {
      const item = parse(buf.subarray(used));
      if (!item) return null;
      used += item.used;
      items.push(item.value);
    }
    return { used, value: items };
  }
  return null;
}

const pct = (sorted, p) => sorted[Math.min(sorted.length - 1, Math.floor(p * sorted.length))];
const summarize = (arr) => {
  const sorted = [...arr].sort((a, b) => a - b);
  return { samples: sorted.length, p50: pct(sorted, 0.5), p95: pct(sorted, 0.95), p99: pct(sorted, 0.99), max: sorted[sorted.length - 1], avg: sorted.reduce((a, b) => a + b, 0) / sorted.length };
};

const socket = await new Promise((resolve, reject) => {
  const s = net.connect(port, "127.0.0.1");
  s.on("connect", () => resolve(s));
  s.on("error", reject);
});
let buffer = Buffer.alloc(0);
const waiters = [];
socket.on("data", (chunk) => {
  buffer = Buffer.concat([buffer, chunk]);
  while (waiters.length) {
    const parsed = parse(buffer);
    if (!parsed) break;
    buffer = buffer.subarray(parsed.used);
    waiters.shift()(parsed.value);
  }
});
const next = () => new Promise((resolve) => waiters.push(resolve));
const encode = (cmd) => "*" + cmd.length + "\r\n" + cmd.map((x) => "$" + Buffer.byteLength(x) + "\r\n" + x + "\r\n").join("");
async function call(...cmd) { socket.write(encode(cmd)); return next(); }

const value = "v".repeat(valueSize);
for (let start = 0; start < keys; start += 2000) {
  const batch = [];
  for (let i = start; i < Math.min(start + 2000, keys); i++) batch.push(call("SET", "bench:" + i, value));
  await Promise.all(batch);
}
for (let i = 0; i < 2000; i++) await call("GET", "bench:" + (i % keys));
for (let i = 0; i < 50; i++) await call("INFO");

const infoLat = [];
for (let i = 0; i < infoCount; i++) {
  const t0 = process.hrtime.bigint();
  await call("INFO");
  infoLat.push(Number(process.hrtime.bigint() - t0) / 1e6);
}
const getLat = [];
const setLat = [];
for (let i = 0; i < ops; i++) {
  const key = "bench:" + (i % keys);
  let t0 = process.hrtime.bigint();
  await call("GET", key);
  getLat.push(Number(process.hrtime.bigint() - t0) / 1e6);
  t0 = process.hrtime.bigint();
  await call("SET", key, value);
  setLat.push(Number(process.hrtime.bigint() - t0) / 1e6);
}

const result = { label, port, keys, valueSize, info: summarize(infoLat), get: summarize(getLat), set: summarize(setLat), at: new Date().toISOString() };
fs.mkdirSync("docs/benchmarks/g0-5/raw", { recursive: true });
fs.writeFileSync("docs/benchmarks/g0-5/raw/" + label + "-run.json", JSON.stringify(result, null, 2));
console.log(JSON.stringify(result, null, 2));
socket.end();
