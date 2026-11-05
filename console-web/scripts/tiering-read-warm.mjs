#!/usr/bin/env node
/** 读取预热：随机 GET 产生 BlockCache 命中/未命中与延迟样本。 */
import net from 'node:net'

const PORT = Number(process.argv[2] || 6380)
const KEYS = Number(process.argv[3] || 60000)
const MAX_KEY = Number(process.argv[4] || 2400000)

const socket = net.connect(PORT, '127.0.0.1')
socket.setNoDelay(true)
await new Promise((res, rej) => { socket.once('connect', res); socket.once('error', rej) })
let buffer = ''
const waiters = []
socket.on('data', (chunk) => {
  buffer += chunk.toString('utf8')
  for (;;) {
    const eol = buffer.indexOf('\r\n')
    if (eol < 0) break
    buffer = buffer.slice(0, 0)
    const w = waiters.shift()
    if (w) w()
    break
  }
})

function encode(parts) {
  let out = '*' + parts.length + '\r\n'
  for (const p of parts) {
    const s = String(p)
    out += '$' + Buffer.byteLength(s) + '\r\n' + s + '\r\n'
  }
  return out
}

let sent = 0
for (let i = 0; i < KEYS; i += 200) {
  let payload = ''
  for (let j = 0; j < 200; j++) {
    const idx = Math.floor(Math.random() * MAX_KEY)
    payload += encode(['GET', 'tier:' + idx])
  }
  socket.write(payload)
  sent += 200
  await new Promise((r) => setTimeout(r, 3))
}
await new Promise((r) => setTimeout(r, 1500))
console.log('reads', sent)
socket.destroy()
