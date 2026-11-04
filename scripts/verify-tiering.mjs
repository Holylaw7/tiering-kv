#!/usr/bin/env node
/**
 * 冷热分层端到端验证（阶段 B1）
 *
 * 目的：回答三个答辩必问的问题
 *   1) 数据被迁移到冷层后，还能不能读回来（命中率 / 完整性）
 *   2) 冷读比热读慢多少（按写入先后做冷热代理：先写入的更可能已被迁移）
 *   3) 迁移是否真的发生（DBSIZE 只统计热层，冷层目录应有 SSTable）
 *
 * 用法：
 *   node scripts/verify-tiering.mjs --port 6380 --keys 600000 --value 256
 *   node scripts/verify-tiering.mjs --keys 10000 --value 128 --json out.json
 */
import net from 'node:net'
import { writeFileSync } from 'node:fs'

const argv = process.argv.slice(2)
function arg(name, fallback) {
  const i = argv.indexOf('--' + name)
  return i >= 0 && argv[i + 1] !== undefined ? argv[i + 1] : fallback
}

const HOST = arg('host', '127.0.0.1')
const PORT = Number(arg('port', '6380'))
const KEYS = Number(arg('keys', '600000'))
const VALUE_SIZE = Number(arg('value', '256'))
const WRITE_BATCH = Number(arg('write-batch', '500'))
const CONCURRENCY = Number(arg('read-concurrency', '4'))
const PREFIX = arg('prefix', 'verify')
const JSON_OUT = arg('json', '')
const WARM_RATIO = Number(arg('warm-ratio', '0.2'))

function encode(args) {
  let out = '*' + args.length + '\r\n'
  for (const a of args) {
    const s = String(a)
    out += '$' + Buffer.byteLength(s) + '\r\n' + s + '\r\n'
  }
  return out
}

/** 从缓冲区头部消费一个完整 RESP2 应答；不完整返回 null。 */
function consume(buffer) {
  if (buffer.length === 0) return null
  const type = buffer[0]
  const lineEnd = buffer.indexOf('\r\n')
  if (lineEnd < 0) return null
  const line = buffer.slice(1, lineEnd)
  if (type === '+' || type === '-' || type === ':') {
    return { value: { type, text: line }, size: lineEnd + 2 }
  }
  if (type === '$') {
    const len = Number(line)
    if (len < 0) return { value: { type, text: null }, size: lineEnd + 2 }
    const total = lineEnd + 2 + len + 2
    if (buffer.length < total) return null
    return { value: { type, text: buffer.slice(lineEnd + 2, lineEnd + 2 + len) }, size: total }
  }
  if (type === '*') {
    const count = Number(line)
    if (count < 0) return { value: { type, text: null }, size: lineEnd + 2 }
    let offset = lineEnd + 2
    const items = []
    for (let i = 0; i < count; i++) {
      const inner = consume(buffer.slice(offset))
      if (!inner) return null
      items.push(inner.value)
      offset += inner.size
    }
    return { value: { type, items }, size: offset }
  }
  return { value: { type: 'unknown', text: line }, size: lineEnd + 2 }
}

/** 单连接客户端：串行请求（用于精确测延迟）。 */
class Client {
  constructor() {
    this.socket = net.connect(PORT, HOST)
    this.socket.setNoDelay(true)
    this.buffer = ''
    this.waiters = []
    this.ready = new Promise((resolve, reject) => {
      this.socket.once('connect', resolve)
      this.socket.once('error', reject)
    })
    this.socket.on('data', (chunk) => {
      this.buffer += chunk.toString('utf8')
      for (;;) {
        const parsed = consume(this.buffer)
        if (!parsed) break
        this.buffer = this.buffer.slice(parsed.size)
        const waiter = this.waiters.shift()
        if (waiter) waiter(parsed.value)
      }
    })
  }

  async command(...args) {
    const reply = new Promise((resolve) => this.waiters.push(resolve))
    this.socket.write(encode(args))
    return reply
  }

  /** 一次性发送多条命令并等待全部应答（流水线写入用）。 */
  async sendMany(commands) {
    const pending = commands.map(() => new Promise((resolve) => this.waiters.push(resolve)))
    this.socket.write(commands.map((c) => encode(c)).join(''))
    return Promise.all(pending)
  }

  close() {
    this.socket.end()
  }
}

function valueFor(index) {
  const head = 'v:' + index + ':'
  return head + 'x'.repeat(Math.max(0, VALUE_SIZE - head.length))
}

function percentile(sorted, p) {
  if (sorted.length === 0) return 0
  const idx = Math.min(sorted.length - 1, Math.floor(sorted.length * p))
  return sorted[idx]
}

const client0 = new Client()
await client0.ready

// ---------- 阶段 0：清理 ----------
await client0.command('flushall')

// ---------- 阶段 1：写入 ----------
console.log('[verify] 写入 %d 个键，值 %dB，批次 %d …', KEYS, VALUE_SIZE, WRITE_BATCH)
const writeStart = Date.now()
let written = 0
for (let start = 0; start < KEYS; start += WRITE_BATCH) {
  const end = Math.min(KEYS, start + WRITE_BATCH)
  const batch = []
  for (let i = start; i < end; i++) batch.push(['SET', PREFIX + ':' + i, valueFor(i)])
  const replies = await client0.sendMany(batch)
  written += replies.filter((r) => r.type === '+' && r.text === 'OK').length
  if (written % (WRITE_BATCH * 100) === 0) {
    console.log('[verify]   已写入 %d/%d', written, KEYS)
  }
}
const writeSeconds = (Date.now() - writeStart) / 1000
console.log('[verify] 写入完成：%d/%d 成功，%ss，%d ops/s',
  written, KEYS, writeSeconds.toFixed(1), Math.round(written / writeSeconds))

// 等待迁移落盘（水位触发是异步的）
await new Promise((r) => setTimeout(r, 15000))

const mem = await client0.command('info', 'memory')
const stats = await client0.command('info', 'stats')
const dbsize = await client0.command('dbsize')
function field(text, name) {
  const m = new RegExp('^' + name + ':(.*)$', 'm').exec(text ?? '')
  return m ? m[1].trim() : ''
}
console.log('[verify] 写入后：DBSIZE=%s  used_memory=%s  maxmemory=%s',
  dbsize.text, field(mem.text, 'used_memory'), field(mem.text, 'maxmemory'))

// ---------- 阶段 2：全量读回（逐条，测延迟 + 校验值） ----------
console.log('[verify] 全量读回 %d 个键，并发 %d …', KEYS, CONCURRENCY)
const latencies = new Array(KEYS)
const hits = new Array(KEYS)
const mismatches = []
let cursor = 0
let readDone = 0

async function reader() {
  const client = new Client()
  await client.ready
  for (;;) {
    const index = cursor++
    if (index >= KEYS) break
    const started = performance.now()
    const reply = await client.command('GET', PREFIX + ':' + index)
    const cost = performance.now() - started
    latencies[index] = cost
    if (reply.type === '$' && reply.text !== null) {
      hits[index] = true
      if (reply.text !== valueFor(index) && mismatches.length < 5) {
        mismatches.push(index)
      }
    } else {
      hits[index] = false
    }
    readDone++
    if (readDone % 100000 === 0) console.log('[verify]   已读 %d/%d', readDone, KEYS)
  }
  client.close()
}

const readStart = Date.now()
await Promise.all(Array.from({ length: CONCURRENCY }, reader))
const readSeconds = (Date.now() - readStart) / 1000

const hitCount = hits.filter(Boolean).length
const sortedAll = [...latencies].sort((a, b) => a - b)
const head = Math.max(1, Math.floor(KEYS * WARM_RATIO))
const early = latencies.slice(0, head).sort((a, b) => a - b) // 最早写入 → 最可能已在冷层
const late = latencies.slice(KEYS - head).sort((a, b) => a - b) // 最后写入 → 最可能仍热

const buckets = [
  ['<0.2ms', (v) => v < 0.2],
  ['0.2-0.5ms', (v) => v >= 0.2 && v < 0.5],
  ['0.5-1ms', (v) => v >= 0.5 && v < 1],
  ['1-2ms', (v) => v >= 1 && v < 2],
  ['2-5ms', (v) => v >= 2 && v < 5],
  ['>=5ms', (v) => v >= 5]
].map(([label, pred]) => ({
  label,
  count: latencies.filter(pred).length
}))

const summary = {
  keys: KEYS,
  valueSize: VALUE_SIZE,
  written,
  writeSeconds: Number(writeSeconds.toFixed(1)),
  writeOpsPerSec: Math.round(written / writeSeconds),
  readSeconds: Number(readSeconds.toFixed(1)),
  readOpsPerSec: Math.round(KEYS / readSeconds),
  hits: hitCount,
  misses: KEYS - hitCount,
  hitRate: Number((hitCount / KEYS).toFixed(4)),
  valueMismatches: mismatches.length,
  dbsize: Number(dbsize.text),
  usedMemory: Number(field(mem.text, 'used_memory')),
  maxMemory: Number(field(mem.text, 'maxmemory')),
  latency: {
    p50: Number(percentile(sortedAll, 0.5).toFixed(3)),
    p95: Number(percentile(sortedAll, 0.95).toFixed(3)),
    p99: Number(percentile(sortedAll, 0.99).toFixed(3)),
    max: Number(sortedAll[sortedAll.length - 1].toFixed(3))
  },
  earlyWritten: {
    p50: Number(percentile(early, 0.5).toFixed(3)),
    p95: Number(percentile(early, 0.95).toFixed(3)),
    p99: Number(percentile(early, 0.99).toFixed(3))
  },
  lateWritten: {
    p50: Number(percentile(late, 0.5).toFixed(3)),
    p95: Number(percentile(late, 0.95).toFixed(3)),
    p99: Number(percentile(late, 0.99).toFixed(3))
  },
  buckets
}

console.log('')
console.log('[verify] 命中 %d / %d（%s%%），值不一致 %d 个',
  hitCount, KEYS, (summary.hitRate * 100).toFixed(2), mismatches.length)
console.log('[verify] 延迟 P50=%sms P95=%sms P99=%sms max=%sms',
  summary.latency.p50, summary.latency.p95, summary.latency.p99, summary.latency.max)
console.log('[verify] 早写入段（冷候选）P50=%sms P95=%sms | 晚写入段（热候选）P50=%sms P95=%sms',
  summary.earlyWritten.p50, summary.earlyWritten.p95,
  summary.lateWritten.p50, summary.lateWritten.p95)
console.log('[verify] 读吞吐 %d ops/s', summary.readOpsPerSec)
console.log('[verify] 分布：' + summary.buckets.map((b) => b.label + '=' + b.count).join('  '))
if (JSON_OUT) {
  writeFileSync(JSON_OUT, JSON.stringify(summary, null, 2))
  console.log('[verify] 结果写入 ' + JSON_OUT)
}
client0.close()
console.log(JSON.stringify(summary))
