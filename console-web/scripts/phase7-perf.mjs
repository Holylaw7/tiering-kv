#!/usr/bin/env node
/**
 * Phase 7 性能验收：INFO / refreshGauges 对 GET·SET 延迟与吞吐的影响。
 *
 * 做法：同一负载跑两轮，唯一差别是第二轮有一个 INFO 轮询线程（默认 50 次/秒）。
 * 输出两轮的 p50/p95/p99 与吞吐，便于对比。
 */
import net from 'node:net'

const PORT = Number(process.argv[2] || 6380)
const DURATION_MS = Number(process.argv[3] || 4000)
const POLL_HZ = Number(process.argv[4] || 50)

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
      if (type === '+' || type === '-' || type === ':') {
        buffer = buffer.slice(eol + 2)
      } else if (type === '$') {
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
    socket,
    ready: new Promise((res, rej) => { socket.once('connect', res); socket.once('error', rej) }),
    async call(...args) {
      const done = new Promise((res) => waiters.push(res))
      socket.write(encode(args))
      return done
    },
    close() { socket.destroy() }
  }
}

function percentile(sorted, p) {
  if (!sorted.length) return 0
  return sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))]
}

async function run(withPolling) {
  const client = connect()
  await client.ready
  const poller = withPolling ? connect() : null
  if (poller) await poller.ready
  let polling = true
  const pollLoop = withPolling
    ? (async () => {
        while (polling) {
          await poller.call('INFO')
          const wait = Math.max(1, Math.floor(1000 / POLL_HZ))
          await new Promise((r) => setTimeout(r, wait))
        }
      })()
    : Promise.resolve()

  const latencies = []
  let ops = 0
  const started = Date.now()
  const key = 'perf:' + Date.now()
  while (Date.now() - started < DURATION_MS) {
    const t0 = process.hrtime.bigint()
    await client.call('SET', key + ':' + ops, 'v'.repeat(64))
    await client.call('GET', key + ':' + ops)
    const t1 = process.hrtime.bigint()
    latencies.push(Number(t1 - t0) / 1_000_000)
    ops += 2
  }
  polling = false
  await pollLoop
  client.close()
  if (poller) poller.close()
  latencies.sort((a, b) => a - b)
  const elapsed = (Date.now() - started) / 1000
  return {
    mode: withPolling ? 'with-info-polling' : 'baseline',
    ops,
    opsPerSec: Math.round(ops / elapsed),
    p50: Number(percentile(latencies, 0.5).toFixed(3)),
    p95: Number(percentile(latencies, 0.95).toFixed(3)),
    p99: Number(percentile(latencies, 0.99).toFixed(3)),
    max: Number(latencies[latencies.length - 1].toFixed(3))
  }
}

// INFO 自身耗时
async function infoLatency() {
  const client = connect()
  await client.ready
  const samples = []
  for (let i = 0; i < 200; i++) {
    const t0 = process.hrtime.bigint()
    await client.call('INFO')
    samples.push(Number(process.hrtime.bigint() - t0) / 1_000_000)
  }
  client.close()
  samples.sort((a, b) => a - b)
  return {
    samples: samples.length,
    p50: Number(percentile(samples, 0.5).toFixed(3)),
    p95: Number(percentile(samples, 0.95).toFixed(3)),
    max: Number(samples[samples.length - 1].toFixed(3))
  }
}

const baseline = await run(false)
const treatment = await run(true)
const info = await infoLatency()
console.log(JSON.stringify({ baseline, treatment, info }, null, 2))
