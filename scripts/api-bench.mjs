#!/usr/bin/env node
// 控制面接口延迟基线：登录一次后并发压测只读接口，输出 P50/P95/P99
// 用法：node scripts/api-bench.mjs [--base http://127.0.0.1:8080] [--path /dashboard/overview?instanceId=1] [--n 200]
const args = process.argv.slice(2)
function arg(name, fallback) {
  const i = args.indexOf('--' + name)
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback
}

const BASE = arg('base', 'http://127.0.0.1:8080')
const PATH = arg('path', '/dashboard/overview?instanceId=1')
const N = Number(arg('n', '200'))
const CONCURRENCY = Number(arg('concurrency', '8'))

const login = await fetch(BASE + '/api/v1/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'admin', password: 'admin123' })
})
const token = (await login.json())?.data?.token
if (!token) {
  console.error('登录失败')
  process.exit(1)
}

const samples = []
let index = 0
let failures = 0

async function worker() {
  while (index < N) {
    index++
    const started = performance.now()
    const response = await fetch(BASE + '/api/v1' + PATH, {
      headers: { Authorization: 'Bearer ' + token }
    })
    await response.arrayBuffer()
    const cost = performance.now() - started
    if (response.status !== 200) failures++
    samples.push(cost)
  }
}

const startedAt = performance.now()
await Promise.all(Array.from({ length: CONCURRENCY }, worker))
const totalMs = performance.now() - startedAt

samples.sort((a, b) => a - b)
const pick = (p) => samples[Math.min(samples.length - 1, Math.floor(samples.length * p))]
const avg = samples.reduce((sum, v) => sum + v, 0) / samples.length

console.log('target   %s/api/v1%s', BASE, PATH)
console.log('requests %d (concurrency %d), failures %d', N, CONCURRENCY, failures)
console.log('total    %sms, throughput %s req/s', totalMs.toFixed(0), (N / (totalMs / 1000)).toFixed(1))
console.log('latency  avg=%sms p50=%sms p95=%sms p99=%sms max=%sms',
  avg.toFixed(2), pick(0.5).toFixed(2), pick(0.95).toFixed(2), pick(0.99).toFixed(2),
  samples[samples.length - 1].toFixed(2))
