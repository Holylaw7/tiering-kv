#!/usr/bin/env node
// 演示负载生成器：对内核发起混合读写，让控制台仪表盘出现真实曲线
// 用法：node scripts/demo-load.mjs [--host 127.0.0.1] [--port 6379] [--seconds 60] [--conns 4] [--window 64]
import net from 'node:net'

const args = process.argv.slice(2)
function arg(name, fallback) {
  const index = args.indexOf('--' + name)
  return index >= 0 && args[index + 1] ? args[index + 1] : fallback
}

const HOST = arg('host', '127.0.0.1')
const PORT = Number(arg('port', '6379'))
const SECONDS = Number(arg('seconds', '60'))
const CONNS = Number(arg('conns', '4'))
const WINDOW = Number(arg('window', '64'))
const PREFIX = arg('prefix', 'demo')

function encode(parts) {
  let out = '*' + parts.length + '\r\n'
  for (const part of parts) {
    const text = String(part)
    out += '$' + Buffer.byteLength(text) + '\r\n' + text + '\r\n'
  }
  return out
}

/** 从缓冲区头部消费一个完整 RESP2 应答；不完整时返回 null。 */
function consumeReply(buffer) {
  if (buffer.length === 0) return null
  const type = buffer[0]
  const lineEnd = buffer.indexOf('\r\n')
  if (lineEnd < 0) return null
  const line = buffer.slice(1, lineEnd)

  if (type === '+' || type === '-' || type === ':') {
    return { rest: buffer.slice(lineEnd + 2), size: lineEnd + 2 }
  }
  if (type === '$') {
    const length = Number(line)
    if (length < 0) return { rest: buffer.slice(lineEnd + 2), size: lineEnd + 2 }
    const total = lineEnd + 2 + length + 2
    if (buffer.length < total) return null
    return { rest: buffer.slice(total), size: total }
  }
  if (type === '*') {
    const count = Number(line)
    if (count < 0) return { rest: buffer.slice(lineEnd + 2), size: lineEnd + 2 }
    let offset = lineEnd + 2
    for (let i = 0; i < count; i++) {
      const inner = consumeReply(buffer.slice(offset))
      if (!inner) return null
      offset += inner.size
    }
    return { rest: buffer.slice(offset), size: offset }
  }
  // 未知类型：丢弃该行，避免死循环
  return { rest: buffer.slice(lineEnd + 2), size: lineEnd + 2 }
}

function worker(id, deadline, stats) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(PORT, HOST)
    let pending = 0
    let buffer = ''
    let closed = false

    function finish() {
      if (closed) return
      closed = true
      socket.end()
    }

    function pump() {
      while (Date.now() <= deadline && pending < WINDOW) {
        const key = PREFIX + ':k' + Math.floor(Math.random() * 500)
        const roll = Math.random()
        let command
        if (roll < 0.5) {
          command = encode(['SET', key, 'v' + Date.now()])
        } else if (roll < 0.8) {
          command = encode(['GET', key])
        } else if (roll < 0.9) {
          command = encode(['HSET', PREFIX + ':h' + id, 'f' + (Date.now() % 20), 'x'])
        } else {
          command = encode(['INCR', PREFIX + ':counter'])
        }
        stats.sent++
        pending++
        socket.write(command)
      }
      if (Date.now() > deadline && pending === 0) {
        finish()
      }
    }

    socket.on('error', (error) => {
      if (!closed) reject(error)
    })
    socket.on('close', () => {
      closed = true
      resolve()
    })
    socket.on('connect', pump)
    socket.on('data', (chunk) => {
      buffer += chunk.toString('utf8')
      for (;;) {
        const parsed = consumeReply(buffer)
        if (!parsed) break
        buffer = parsed.rest
        pending--
        stats.received++
      }
      pump()
    })
  })
}

const deadline = Date.now() + SECONDS * 1000
const stats = { sent: 0, received: 0 }
const started = Date.now()
console.log('[demo-load] target %s:%d conns=%d during %ds', HOST, PORT, CONNS, SECONDS)

await Promise.all(Array.from({ length: CONNS }, (_, i) => worker(i, deadline, stats)))
const elapsed = Math.max(0.001, (Date.now() - started) / 1000)
console.log('[demo-load] sent=%d replies=%d avgQps=%s',
  stats.sent, stats.received, (stats.sent / elapsed).toFixed(1))
