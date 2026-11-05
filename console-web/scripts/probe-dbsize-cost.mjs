#!/usr/bin/env node
/** 测量 Flush 后首次 DBSIZE / INFO 的成本（冷层存活计数需要全量扫描）。 */
import net from 'node:net'

const PORT = Number(process.argv[2] || 6380)

function encode(parts) {
  let out = '*' + parts.length + '\r\n'
  for (const p of parts) {
    const s = String(p)
    out += '$' + Buffer.byteLength(s) + '\r\n' + s + '\r\n'
  }
  return out
}

function connect() {
  const socket = net.connect(PORT, '127.0.0.1')
  socket.setNoDelay(true)
  let buffer = ''
  const waiters = []
  socket.on('data', (chunk) => {
    buffer += chunk.toString('utf8')
    for (;;) {
      if (!buffer.length) break
      const eol = buffer.indexOf('\r\n')
      if (eol < 0) break
      const type = buffer[0]
      if (type === '$') {
        const len = Number(buffer.slice(1, eol))
        const total = len < 0 ? eol + 2 : eol + 2 + len + 2
        if (buffer.length < total) break
        buffer = buffer.slice(total)
      } else {
        buffer = buffer.slice(eol + 2)
      }
      const w = waiters.shift()
      if (w) w()
    }
  })
  return {
    ready: new Promise((res, rej) => { socket.once('connect', res); socket.once('error', rej) }),
    async call(...args) {
      const done = new Promise((res) => waiters.push(res))
      socket.write(encode(args))
      return done
    },
    close() { socket.destroy() }
  }
}

const client = connect()
await client.ready

async function timeIt(label, ...args) {
  const t0 = process.hrtime.bigint()
  await client.call(...args)
  const ms = Number(process.hrtime.bigint() - t0) / 1_000_000
  console.log(label.padEnd(28), ms.toFixed(2) + ' ms')
  return ms
}

const results = []
for (let i = 0; i < 3; i++) results.push(await timeIt('DBSIZE #' + (i + 1), 'DBSIZE'))
for (let i = 0; i < 3; i++) results.push(await timeIt('INFO #' + (i + 1), 'INFO'))
client.close()
console.log(JSON.stringify({ samples: results.map((v) => Number(v.toFixed(2))) }))
