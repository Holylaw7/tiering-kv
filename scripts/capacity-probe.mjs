#!/usr/bin/env node
/**
 * 容量模型探测（TD-084）：测"每键边际内存开销"。
 *
 * 做法：清空后分两批写入（键名不重叠），用第二批的增量除以键数，
 * 剔除首写时的一次性固定开销（段分配、WAL 缓冲等）。
 *
 * 用法：node scripts/capacity-probe.mjs --port 6381 --keys 100000 --value 100
 */
import net from 'node:net'

const argv = process.argv.slice(2)
function arg(name, fallback) {
  const i = argv.indexOf('--' + name)
  return i >= 0 && argv[i + 1] !== undefined ? argv[i + 1] : fallback
}

const HOST = arg('host', '127.0.0.1')
const PORT = Number(arg('port', '6381'))
const KEYS = Number(arg('keys', '100000'))
const VALUE_SIZE = Number(arg('value', '100'))
const BATCH = Number(arg('batch', '1000'))
const TAG = arg('tag', 'A')

function encode(parts) {
  let out = '*' + parts.length + '\r\n'
  for (const p of parts) {
    const s = String(p)
    out += '$' + Buffer.byteLength(s) + '\r\n' + s + '\r\n'
  }
  return out
}

function consume(buffer) {
  if (!buffer.length) return null
  const type = buffer[0]
  const eol = buffer.indexOf('\r\n')
  if (eol < 0) return null
  const line = buffer.slice(1, eol)
  if (type === '+' || type === '-' || type === ':') return { text: line, size: eol + 2 }
  if (type === '$') {
    const len = Number(line)
    if (len < 0) return { text: null, size: eol + 2 }
    const total = eol + 2 + len + 2
    if (buffer.length < total) return null
    return { text: buffer.slice(eol + 2, eol + 2 + len), size: total }
  }
  return { text: line, size: eol + 2 }
}

const socket = net.connect(PORT, HOST)
socket.setNoDelay(true)
await new Promise((res, rej) => { socket.once('connect', res); socket.once('error', rej) })

let buffer = ''
const waiters = []
socket.on('data', (chunk) => {
  buffer += chunk.toString('utf8')
  for (;;) {
    const parsed = consume(buffer)
    if (!parsed) break
    buffer = buffer.slice(parsed.size)
    const w = waiters.shift()
    if (w) w(parsed.text)
  }
})

function send(cmds) {
  const pending = cmds.map(() => new Promise((res) => waiters.push(res)))
  socket.write(cmds.map((c) => encode(c)).join(''))
  return Promise.all(pending)
}

async function usedMemory() {
  const [text] = await send([['INFO', 'memory']])
  const m = /^used_memory:(\d+)$/m.exec(text ?? '')
  return m ? Number(m[1]) : -1
}

const value = 'v'.repeat(VALUE_SIZE)
async function writeBatch(tag) {
  const started = Date.now()
  for (let i = 0; i < KEYS; i += BATCH) {
    const batch = []
    for (let j = i; j < Math.min(KEYS, i + BATCH); j++) batch.push(['SET', 'cap:' + tag + ':' + j, value])
    await send(batch)
  }
  return (Date.now() - started) / 1000
}

await send([['FLUSHALL']])
await new Promise((r) => setTimeout(r, 500))
const m0 = await usedMemory()
await writeBatch('warm')
await new Promise((r) => setTimeout(r, 500))
const m1 = await usedMemory()
const seconds = await writeBatch(TAG)
await new Promise((r) => setTimeout(r, 500))
const m2 = await usedMemory()
const [dbsize] = await send([['DBSIZE']])

const marginal = (m2 - m1) / KEYS
const result = {
  valueSize: VALUE_SIZE,
  keysPerBatch: KEYS,
  writeSeconds: Number(seconds.toFixed(1)),
  opsPerSec: Math.round(KEYS / seconds),
  usedMemoryStart: m0,
  usedMemoryAfterWarm: m1,
  usedMemoryAfterSecond: m2,
  marginalBytesPerKey: Number(marginal.toFixed(1)),
  overheadBytesPerKey: Number((marginal - VALUE_SIZE).toFixed(1)),
  keysPerGb: marginal > 0 ? Math.floor((1024 * 1024 * 1024) / marginal) : 0,
  dbsize: Number(dbsize)
}
console.log(JSON.stringify(result))
socket.end()
