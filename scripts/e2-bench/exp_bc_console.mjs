#!/usr/bin/env node
/**
 * Experiments B & C: Control-Plane Full-Stack Performance & Multi-Scale Backup/Verify/Restore Benchmark
 *   - Mode "bc-local": Runs B1 (20 REST APIs, N=100), B2 (10/20/50 concurrency pool stress),
 *                      B3 (13 Playwright routes first-paint), and C-Local (1K/10K/50K/100K keys)
 *   - Mode "c-s3":     Runs C-S3 (1K/10K/50K/100K keys against live RustFS S3 backend)
 *
 * Usage:
 *   node scripts/e2-bench/exp_bc_console.mjs --mode bc-local --out <path.json>
 *   node scripts/e2-bench/exp_bc_console.mjs --mode c-s3 --out <path.json>
 */
import net from 'node:net'
import { execSync } from 'node:child_process'
import { writeFileSync, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { createRequire } from 'node:module'

const args = process.argv.slice(2)
function arg(name, fallback) {
  const i = args.indexOf('--' + name)
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback
}

const MODE = arg('mode', 'bc-local')
const BASE = arg('base', 'http://127.0.0.1:8081')
const REDIS_PORT = Number(arg('redis-port', '6379'))
const OUT = arg('out', 'exp_bc.json')
const SCALES = arg('scales', '1000,10000,50000,100000')
  .split(',')
  .map((x) => Number(x.trim()))

function percentile(sorted, p) {
  if (!sorted.length) return 0
  return Number(sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))].toFixed(2))
}

function summarize(samples) {
  const sorted = [...samples].sort((a, b) => a - b)
  const avg = sorted.reduce((s, v) => s + v, 0) / Math.max(1, sorted.length)
  return {
    n: sorted.length,
    avgMs: Number(avg.toFixed(2)),
    p50Ms: percentile(sorted, 0.5),
    p95Ms: percentile(sorted, 0.95),
    p99Ms: percentile(sorted, 0.99),
    maxMs: Number((sorted[sorted.length - 1] ?? 0).toFixed(2))
  }
}

async function apiJson(path, token, init = {}) {
  const headers = { ...(init.headers || {}) }
  if (token) headers.Authorization = `Bearer ${token}`
  if (init.body && !headers['Content-Type']) headers['Content-Type'] = 'application/json'
  const res = await fetch(`${BASE}${path}`, { ...init, headers })
  const text = await res.text()
  let json = null
  try {
    json = JSON.parse(text)
  } catch {
    // ignore non-json
  }
  return { status: res.status, headers: res.headers, json, text }
}

async function loginAdmin() {
  const res = await apiJson('/api/v1/auth/login', null, {
    method: 'POST',
    body: JSON.stringify({ username: 'admin', password: 'admin123' })
  })
  if (res.status !== 200 || !res.json?.data?.token) {
    throw new Error(`Admin login failed: ${res.status} ${res.text}`)
  }
  return res.json.data.token
}

function encodeResp(parts) {
  let s = `*${parts.length}\r\n`
  for (const p of parts) {
    const str = String(p)
    s += `$${Buffer.byteLength(str)}\r\n${str}\r\n`
  }
  return s
}

async function sendRespPipeline(port, commands, batchSize = 500) {
  return new Promise((resolvePromise, reject) => {
    const sock = net.connect(port, '127.0.0.1')
    sock.setNoDelay(true)
    let buf = Buffer.alloc(0)
    let pending = 0
    let idx = 0

    function writeNextBatch() {
      if (idx >= commands.length) {
        sock.end()
        resolvePromise()
        return
      }
      const end = Math.min(commands.length, idx + batchSize)
      let payload = ''
      for (let i = idx; i < end; i++) payload += encodeResp(commands[i])
      pending = end - idx
      idx = end
      sock.write(payload)
    }

    sock.on('connect', writeNextBatch)
    sock.on('data', (chunk) => {
      buf = Buffer.concat([buf, chunk])
      while (pending > 0) {
        const nl = buf.indexOf('\r\n')
        if (nl < 0) break
        const first = buf[0]
        if (first === 36) {
          // bulk string
          const len = Number(buf.subarray(1, nl).toString('ascii'))
          if (len < 0) {
            buf = buf.subarray(nl + 2)
            pending--
          } else {
            const total = nl + 2 + len + 2
            if (buf.length < total) break
            buf = buf.subarray(total)
            pending--
          }
        } else {
          const line = buf.subarray(0, nl).toString('utf8')
          buf = buf.subarray(nl + 2)
          if (line.startsWith('-')) {
            sock.destroy()
            reject(new Error(`RESP error: ${line}`))
            return
          }
          pending--
        }
      }
      if (pending === 0) writeNextBatch()
    })
    sock.on('error', reject)
  })
}

async function seedMixedKeys(port, totalKeys, prefix) {
  const cmds = []
  const strPayload = 'S'.repeat(80)
  const strCount = Math.floor(totalKeys * 0.8)
  const hashCount = Math.floor(totalKeys * 0.1)
  const ttlCount = totalKeys - strCount - hashCount

  for (let i = 0; i < strCount; i++) {
    cmds.push(['SET', `${prefix}:s:${i}`, `${strPayload}-${i}`])
  }
  for (let i = 0; i < hashCount; i++) {
    cmds.push(['HSET', `${prefix}:h:${i}`, 'f1', `val-${i}`, 'f2', 'tier-hot'])
  }
  for (let i = 0; i < ttlCount; i++) {
    cmds.push(['SET', `${prefix}:t:${i}`, `ttl-${i}`, 'EX', '7200'])
  }
  const t0 = performance.now()
  await sendRespPipeline(port, cmds, 500)
  return {
    totalKeys,
    strCount,
    hashCount,
    ttlCount,
    seedMs: Math.round(performance.now() - t0)
  }
}

async function flushCore(port) {
  await sendRespPipeline(port, [['FLUSHALL']], 1)
}

function readPromPoolMetrics(token) {
  try {
    const out = execSync(
      `docker exec tk-console curl -fsS -H "Authorization: Bearer ${token}" http://127.0.0.1:8080/actuator/prometheus`,
      { encoding: 'utf8', maxBuffer: 4 * 1024 * 1024 }
    )
    const map = {}
    for (const line of out.split(/\r?\n/)) {
      if (line.startsWith('tieringkv_console_pool_') && !line.startsWith('#')) {
        const [k, v] = line.trim().split(/\s+/)
        map[k] = Number(v)
      }
    }
    return map
  } catch (e) {
    return { error: String(e.message) }
  }
}

async function runB1Apis(token, instanceId) {
  console.log('[B1] Seeding baseline dataset (100 keys + Vector + Backup) for 20-API latency suite...')
  await flushCore(REDIS_PORT)
  await seedMixedKeys(REDIS_PORT, 100, 'b1:data')
  // Create 1 vector collection + 1 backup so verify/preview/vector APIs have real data
  await apiJson('/api/v1/multimodel/vector/add', token, {
    method: 'POST',
    body: JSON.stringify({ instanceId, collection: 'b1_vec', id: 'v1', values: [0.9, 0.1, 0.0, 0.0] })
  })
  const bCreate = await apiJson(`/api/v1/backups?instanceId=${instanceId}`, token, { method: 'POST' })
  const backupId = bCreate.json.data.id
  for (let i = 0; i < 40; i++) {
    const list = await apiJson(`/api/v1/backups?instanceId=${instanceId}`, token)
    const rec = list.json.data.find((r) => r.id === backupId)
    if (rec?.status === 'SUCCESS') break
    await new Promise((r) => setTimeout(r, 300))
  }

  const endpoints = [
    { name: 'POST /auth/login', method: 'POST', path: '/api/v1/auth/login', auth: false, body: { username: 'admin', password: 'admin123' } },
    { name: 'GET /auth/me', method: 'GET', path: '/api/v1/auth/me' },
    { name: 'GET /users', method: 'GET', path: '/api/v1/users' },
    { name: 'GET /instances', method: 'GET', path: '/api/v1/instances' },
    { name: 'GET /dashboard/overview', method: 'GET', path: `/api/v1/dashboard/overview?instanceId=${instanceId}` },
    { name: 'GET /dashboard/trends', method: 'GET', path: `/api/v1/dashboard/trends?instanceId=${instanceId}&minutes=30` },
    { name: 'GET /cluster/topology', method: 'GET', path: `/api/v1/cluster/topology?instanceId=${instanceId}` },
    { name: 'GET /keys/scan', method: 'GET', path: `/api/v1/keys/scan?instanceId=${instanceId}&cursor=0&count=50` },
    { name: 'POST /keys/analyze', method: 'POST', path: `/api/v1/keys/analyze?instanceId=${instanceId}`, body: { pattern: '*', maxKeys: 500, delimiter: ':', topN: 10 } },
    { name: 'GET /keys/detail', method: 'GET', path: `/api/v1/keys/detail?instanceId=${instanceId}&key=b1:data:s:0` },
    { name: 'GET /multimodel/vector/collections', method: 'GET', path: `/api/v1/multimodel/vector/collections?instanceId=${instanceId}` },
    { name: 'POST /terminal/execute', method: 'POST', path: '/api/v1/terminal/execute', body: { instanceId, command: 'PING' } },
    { name: 'GET /slowlog', method: 'GET', path: `/api/v1/slowlog?instanceId=${instanceId}&limit=20` },
    { name: 'GET /alerts/rules', method: 'GET', path: '/api/v1/alerts/rules' },
    { name: 'GET /alerts/events', method: 'GET', path: '/api/v1/alerts/events?page=1&size=20' },
    { name: 'GET /backups', method: 'GET', path: `/api/v1/backups?instanceId=${instanceId}` },
    { name: 'POST /backups/{id}/verify', method: 'POST', path: `/api/v1/backups/${backupId}/verify` },
    { name: 'POST /backups/{id}/restore/preview', method: 'POST', path: `/api/v1/backups/${backupId}/restore/preview` },
    { name: 'GET /audit', method: 'GET', path: '/api/v1/audit?page=1&size=20' },
    { name: 'GET /system/info', method: 'GET', path: `/api/v1/system/info?instanceId=${instanceId}` }
  ]

  const out = []
  for (const ep of endpoints) {
    const reqInit = {
      method: ep.method,
      headers: ep.auth === false ? {} : { Authorization: `Bearer ${token}` }
    }
    if (ep.body) {
      reqInit.headers['Content-Type'] = 'application/json'
      reqInit.body = JSON.stringify(ep.body)
    }
    // 10 warmups
    for (let w = 0; w < 10; w++) {
      await fetch(`${BASE}${ep.path}`, reqInit).then((r) => r.arrayBuffer())
    }
    const samples = []
    let failures = 0
    const N = ep.name === 'POST /auth/login' ? 50 : 100
    for (let i = 0; i < N; i++) {
      const t0 = performance.now()
      const res = await fetch(`${BASE}${ep.path}`, reqInit)
      await res.arrayBuffer()
      const dt = performance.now() - t0
      if (res.status !== 200) failures++
      samples.push(dt)
    }
    const stat = { name: ep.name, failures, ...summarize(samples) }
    console.log(`  ${ep.name.padEnd(36)} p50=${stat.p50Ms}ms p95=${stat.p95Ms}ms p99=${stat.p99Ms}ms fail=${failures}`)
    out.push(stat)
  }

  // Also benchmark internal /actuator/prometheus scrape (20 samples)
  const promSamples = []
  for (let i = 0; i < 20; i++) {
    const t0 = performance.now()
    execSync(`docker exec tk-console curl -fsS -H "Authorization: Bearer ${token}" http://127.0.0.1:8080/actuator/prometheus > NUL`, {
      shell: 'cmd.exe'
    })
    promSamples.push(performance.now() - t0)
  }

  await apiJson(`/api/v1/backups/${backupId}`, token, { method: 'DELETE' })
  return { endpoints: out, prometheusScrapeExec: summarize(promSamples) }
}

async function runB2Concurrency(token, instanceId) {
  console.log('[B2] Running bounded RESP pool concurrency stress (c=10, 20, 50)...')
  const levels = [10, 20, 50]
  const results = []
  const targets = [
    { key: 'overview', path: `/api/v1/dashboard/overview?instanceId=${instanceId}` },
    { key: 'keys_scan', path: `/api/v1/keys/scan?instanceId=${instanceId}&cursor=0&count=20` }
  ]

  for (const target of targets) {
    for (const c of levels) {
      const totalReqs = 400
      let next = 0
      let failures = 0
      const samples = []
      const poolBefore = readPromPoolMetrics(token)
      const tStart = performance.now()
      await Promise.all(
        Array.from({ length: c }, async () => {
          while (next < totalReqs) {
            next++
            const t0 = performance.now()
            const res = await fetch(`${BASE}${target.path}`, {
              headers: { Authorization: `Bearer ${token}` }
            })
            await res.arrayBuffer()
            if (res.status !== 200) failures++
            samples.push(performance.now() - t0)
          }
        })
      )
      const totalMs = performance.now() - tStart
      const poolAfter = readPromPoolMetrics(token)
      const rps = Number(((totalReqs / totalMs) * 1000).toFixed(1))
      const row = {
        target: target.key,
        concurrency: c,
        totalReqs,
        failures,
        rps,
        ...summarize(samples),
        poolBefore,
        poolAfter
      }
      console.log(`  [${target.key}] c=${c} -> ${rps} req/s | p50=${row.p50Ms}ms p95=${row.p95Ms}ms p99=${row.p99Ms}ms fail=${failures}`)
      results.push(row)
    }
  }
  return results
}

async function runB3FrontendRoutes() {
  console.log('[B3] Measuring 13 frontend routes first-paint timing via Playwright Chromium...')
  const require = createRequire(import.meta.url)
  const { chromium } = require(resolve('console-web/node_modules/@playwright/test'))
  const browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const page = await context.newPage()

  const routes = [
    { path: '/login', label: '登录页', auth: false },
    { path: '/dashboard', label: '运行总览', auth: true },
    { path: '/topology', label: '集群拓扑', auth: true },
    { path: '/instances', label: '实例管理', auth: true },
    { path: '/keyspace', label: '键空间', auth: true },
    { path: '/multimodel', label: '多模型工作台', auth: true },
    { path: '/terminal', label: '在线终端', auth: true },
    { path: '/alerts', label: '告警中心', auth: true },
    { path: '/slowlog', label: '慢查询日志', auth: true },
    { path: '/backups', label: '备份恢复', auth: true },
    { path: '/audit', label: '审计日志', auth: true },
    { path: '/system', label: '系统信息', auth: true },
    { path: '/users', label: '用户管理', auth: true }
  ]

  const out = []
  // Measure /login first (unauthenticated)
  const loginSamples = []
  for (let r = 0; r < 5; r++) {
    const t0 = performance.now()
    await page.goto(`${BASE}/#/login`, { waitUntil: 'networkidle' })
    await page.getByTestId('login-submit').waitFor({ state: 'visible' })
    loginSamples.push(performance.now() - t0)
  }
  out.push({ path: '/login', label: '登录页', ...summarize(loginSamples) })

  // Sign in once
  await page.getByPlaceholder('admin').fill('admin')
  await page.getByPlaceholder('请输入密码').fill('admin123')
  await page.getByTestId('login-submit').click()
  await page.waitForURL(/#\/dashboard/)
  await page.waitForLoadState('networkidle')

  for (const rt of routes.filter((x) => x.auth)) {
    const samples = []
    for (let r = 0; r < 5; r++) {
      await page.goto(`${BASE}/#${rt.path}`, { waitUntil: 'domcontentloaded' })
      // Hard reload to measure true cold SPA route load + API ready
      const t0 = performance.now()
      await page.reload({ waitUntil: 'networkidle' })
      await page.locator('.page-title, h2').first().waitFor({ state: 'visible', timeout: 10000 })
      samples.push(performance.now() - t0)
    }
    const stat = { path: rt.path, label: rt.label, ...summarize(samples) }
    console.log(`  route ${rt.path.padEnd(14)} p50=${stat.p50Ms}ms avg=${stat.avgMs}ms`)
    out.push(stat)
  }

  await browser.close()
  return out
}

async function runExpCBackupScale(token, instanceId, storageLabel) {
  console.log(`[C-${storageLabel}] Running multi-scale backup/verify/preview/restore benchmark (${SCALES.join(', ')})...`)
  const capRes = await apiJson('/api/v1/backups/capacity', token)
  const storageKey = capRes.json?.data?.storageKey || storageLabel
  const rows = []

  for (const scale of SCALES) {
    const prefix = `e2c:${storageLabel}:${scale}`
    await flushCore(REDIS_PORT)
    const seeded = await seedMixedKeys(REDIS_PORT, scale, prefix)
    console.log(`  [${storageLabel} ${scale}] seeded in ${seeded.seedMs}ms`)

    // 1. Async Logical Backup Export (Streaming Gzip + SHA-256)
    const tExp = performance.now()
    const createRes = await apiJson(`/api/v1/backups?instanceId=${instanceId}`, token, { method: 'POST' })
    if (createRes.status !== 200) {
      throw new Error(`Backup create failed (${scale}): ${createRes.status} ${createRes.text}`)
    }
    const backupId = createRes.json.data.id
    let rec = null
    for (let poll = 0; poll < 600; poll++) {
      const list = await apiJson(`/api/v1/backups?instanceId=${instanceId}`, token)
      rec = list.json.data.find((x) => x.id === backupId)
      if (rec && rec.status !== 'RUNNING') break
      await new Promise((r) => setTimeout(r, 200))
    }
    const exportMs = Math.round(performance.now() - tExp)
    if (!rec || rec.status !== 'SUCCESS') {
      throw new Error(`Backup did not succeed (${scale}): ${JSON.stringify(rec)}`)
    }

    // 2. Full-file SHA-256 + Gzip/JSONL Verify
    const tVer = performance.now()
    const verRes = await apiJson(`/api/v1/backups/${backupId}/verify`, token, { method: 'POST' })
    const verifyMs = Math.round(performance.now() - tVer)
    if (verRes.status !== 200 || verRes.json?.data?.status !== 'VERIFIED') {
      throw new Error(`Verify failed (${scale}): ${verRes.status} ${verRes.text}`)
    }

    // 3. Mutate 10% + delete 10% -> Dry-Run conflict preview
    const mutateCount = Math.floor(seeded.strCount * 0.125) // 10% of total
    const delCount = Math.floor(seeded.strCount * 0.125)
    const mutCmds = []
    for (let i = 0; i < mutateCount; i++) mutCmds.push(['SET', `${prefix}:s:${i}`, `mutated-${i}`])
    for (let i = mutateCount; i < mutateCount + delCount; i++) mutCmds.push(['DEL', `${prefix}:s:${i}`])
    await sendRespPipeline(REDIS_PORT, mutCmds, 500)

    const tPrev = performance.now()
    const prevRes = await apiJson(`/api/v1/backups/${backupId}/restore/preview`, token, { method: 'POST' })
    const previewMs = Math.round(performance.now() - tPrev)
    const preview = prevRes.json?.data

    // 4. Async Pipeline Restore (pure restore without safety snapshot)
    const tRest = performance.now()
    const restRes = await apiJson(`/api/v1/backups/${backupId}/restore`, token, {
      method: 'POST',
      body: JSON.stringify({ createSafetySnapshot: false })
    })
    if (restRes.status !== 200) {
      throw new Error(`Restore submit failed (${scale}): ${restRes.status} ${restRes.text}`)
    }
    let restRec = null
    for (let poll = 0; poll < 600; poll++) {
      const list = await apiJson(`/api/v1/backups?instanceId=${instanceId}`, token)
      restRec = list.json.data.find((x) => x.id === backupId)
      if (restRec && restRec.restoreStatus !== 'RESTORING') break
      await new Promise((r) => setTimeout(r, 200))
    }
    const restoreMs = Math.round(performance.now() - tRest)
    if (!restRec || restRec.restoreStatus !== 'SUCCEEDED') {
      throw new Error(`Restore did not succeed (${scale}): ${JSON.stringify(restRec)}`)
    }

    // 5. On 10,000 keys, also benchmark restore WITH createSafetySnapshot=true
    let safetyRestoreMs = null
    let safetyBackupId = null
    if (scale === 10000) {
      const tSafe = performance.now()
      await apiJson(`/api/v1/backups/${backupId}/restore`, token, {
        method: 'POST',
        body: JSON.stringify({ createSafetySnapshot: true })
      })
      for (let poll = 0; poll < 600; poll++) {
        const list = await apiJson(`/api/v1/backups?instanceId=${instanceId}`, token)
        const cur = list.json.data.find((x) => x.id === backupId)
        if (cur && cur.restoreStatus !== 'RESTORING') {
          safetyBackupId = cur.preRestoreBackupId
          break
        }
        await new Promise((r) => setTimeout(r, 200))
      }
      safetyRestoreMs = Math.round(performance.now() - tSafe)
    }

    const row = {
      storage: storageLabel,
      storageKey,
      scale,
      keyCount: rec.keyCount,
      sizeBytes: rec.sizeBytes,
      exportMs,
      exportKeysPerSec: Math.round((rec.keyCount / Math.max(1, exportMs)) * 1000),
      verifyMs,
      verifyEntries: verRes.json.data.validEntries,
      previewMs,
      previewConflicts: preview?.conflictKeys,
      previewNew: preview?.newKeys,
      restoreMs,
      restoreKeysPerSec: Math.round((restRec.restoredKeys / Math.max(1, restoreMs)) * 1000),
      safetyRestoreMs
    }
    console.log(
      `  [${storageLabel} ${scale}] export=${exportMs}ms (${row.exportKeysPerSec} k/s, ${(rec.sizeBytes / 1024).toFixed(1)} KB) | verify=${verifyMs}ms | preview=${previewMs}ms | restore=${restoreMs}ms (${row.restoreKeysPerSec} k/s)${safetyRestoreMs ? ` | w/safety=${safetyRestoreMs}ms` : ''}`
    )
    rows.push(row)

    // Clean backup artifacts so disk/S3 stays clean
    if (safetyBackupId) {
      await apiJson(`/api/v1/backups/${safetyBackupId}`, token, { method: 'DELETE' })
    }
    await apiJson(`/api/v1/backups/${backupId}`, token, { method: 'DELETE' })
    await new Promise((r) => setTimeout(r, 500))
  }

  await flushCore(REDIS_PORT)
  return { storageKey, rows }
}

async function main() {
  const token = await loginAdmin()
  const instRes = await apiJson('/api/v1/instances', token)
  const instanceId = instRes.json.data[0].id
  const result = {
    timestamp: new Date().toISOString(),
    mode: MODE,
    base: BASE,
    instanceId
  }

  if (MODE === 'bc-local') {
    result.B1_apis = await runB1Apis(token, instanceId)
    result.B2_concurrency = await runB2Concurrency(token, instanceId)
    result.B3_routes = await runB3FrontendRoutes()
    result.C_local = await runExpCBackupScale(token, instanceId, 'local')
  } else if (MODE === 'c-s3') {
    result.C_s3 = await runExpCBackupScale(token, instanceId, 's3')
  } else {
    throw new Error(`Unknown mode: ${MODE}`)
  }

  mkdirSync(dirname(OUT), { recursive: true })
  writeFileSync(OUT, JSON.stringify(result, null, 2), 'utf8')
  console.log(`[DONE] Saved ${MODE} results to ${OUT}`)
}

main().catch((err) => {
  console.error(err)
  process.exit(1)
})
