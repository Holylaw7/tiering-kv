#!/usr/bin/env node
/** 分批写入：每轮写一批后等待 Flush 完成，用于制造多次 Flush / 触发 Compaction。 */
import net from 'node:net'

const PORT = Number(process.argv[2] || 6380)
const ROUNDS = Number(process.argv[3] || 10)
const PER_ROUND = Number(process.argv[4] || 200000)
const PAUSE_MS = Number(process.argv[5] || 1500)
const VALUE = 32

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

const value = 'x'.repeat(VALUE)
let seq = 0
for (let round = 1; round <= ROUNDS; round++) {
  for (let i = 0; i < PER_ROUND; i += 1000) {
    let payload = ''
    for (let j = 0; j < 1000; j++) {
      payload += encode(['SET', 'tier:' + (seq++), value])
    }
    socket.write(payload)
    await new Promise((r) => setTimeout(r, 2))
  }
  console.log('round', round, 'written', seq)
  await new Promise((r) => setTimeout(r, PAUSE_MS))
}
console.log('done', seq)
socket.destroy()
