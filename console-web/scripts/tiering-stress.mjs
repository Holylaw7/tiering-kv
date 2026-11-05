#!/usr/bin/env node
/** 写入压力：制造多次 Flush 以触发 Compaction（TD-096 演示/验证用）。 */
import net from 'node:net'

const PORT = Number(process.argv[2] || 6380)
const KEYS = Number(process.argv[3] || 3_000_000)
const VALUE = Number(process.argv[4] || 32)
const BATCH = 2000

const socket = net.connect(PORT, '127.0.0.1')
socket.setNoDelay(true)
await new Promise((res, rej) => { socket.once('connect', res); socket.once('error', rej) })

function encode(parts) {
  let out = '*' + parts.length + '\r\n'
  for (const p of parts) {
    const s = String(p)
    out += '$' + Buffer.byteLength(s) + '\r\n' + s + '\r\n'
  }
  return out
}

let pending = 0
const started = Date.now()
const value = 'x'.repeat(VALUE)

for (let i = 0; i < KEYS; i += BATCH) {
  let payload = ''
  for (let j = i; j < Math.min(KEYS, i + BATCH); j++) {
    payload += encode(['SET', 'stress:' + j, value])
  }
  pending += BATCH
  socket.write(payload)
  if (pending >= 20_000) {
    await new Promise((r) => setTimeout(r, 1))
    pending = 0
  }
  if (i % 200_000 === 0) {
    console.log('written', i, 'in', ((Date.now() - started) / 1000).toFixed(1) + 's')
  }
}
await new Promise((r) => setTimeout(r, 1500))
console.log('done', KEYS, 'keys in', ((Date.now() - started) / 1000).toFixed(1) + 's')
socket.destroy()
