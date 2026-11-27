#!/usr/bin/env node
/**
 * Experiment E: Seed rich realistic data into live Tiering-KV v6.4.0 Console stack
 * and capture 16 Retina (2x HiDPI, 1440x900) UI screenshots into docs/design/figures/
 * plus updated README screenshots in console-web/screenshots/.
 */
import net from 'node:net'
import { copyFileSync, mkdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { createRequire } from 'node:module'

const args = process.argv.slice(2)
function arg(name, fallback) {
  const i = args.indexOf('--' + name)
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback
}

const BASE = arg('base', 'http://127.0.0.1:8081')
const REDIS_PORT = Number(arg('redis-port', '6379'))
const SKIP_SEED = args.includes('--skip-seed')
const FIG_DIR = resolve('docs/design/figures')
const README_SHOT_DIR = resolve('console-web/screenshots')

mkdirSync(FIG_DIR, { recursive: true })
mkdirSync(README_SHOT_DIR, { recursive: true })

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
    // ignore
  }
  return { status: res.status, json, text }
}

function encodeResp(parts) {
  let s = `*${parts.length}\r\n`
  for (const p of parts) {
    const str = String(p)
    s += `$${Buffer.byteLength(str)}\r\n${str}\r\n`
  }
  return s
}

async function sendRespPipeline(port, commands, batchSize = 200) {
  return new Promise((resolvePromise, reject) => {
    const sock = net.connect(port, '127.0.0.1')
    sock.setNoDelay(true)
    let buf = Buffer.alloc(0)
    let pending = 0
    let idx = 0

    function writeNext() {
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

    sock.on('connect', writeNext)
    sock.on('data', (chunk) => {
      buf = Buffer.concat([buf, chunk])
      while (pending > 0) {
        const nl = buf.indexOf('\r\n')
        if (nl < 0) break
        const first = buf[0]
        if (first === 36) {
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
          buf = buf.subarray(nl + 2)
          pending--
        }
      }
      if (pending === 0) writeNext()
    })
    sock.on('error', reject)
  })
}

async function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms))
}

async function seedRichData(token, instanceId) {
  console.log('[E-seed] Flushing & seeding rich multi-model / multi-prefix / bigkey dataset...')
  await sendRespPipeline(REDIS_PORT, [['FLUSHALL']], 1)

  const cmds = []
  // 1. Multi-prefix keyspace with varied types & TTL buckets
  for (let i = 1; i <= 40; i++) {
    cmds.push(['HSET', `user:profile:${1000 + i}`, 'name', `user_${i}`, 'tier', i % 3 === 0 ? 'VIP' : 'STD', 'city', 'Shanghai'])
  }
  for (let i = 1; i <= 60; i++) {
    cmds.push(['SET', `order:detail:${2000 + i}`, `{"orderId":${2000 + i},"amount":${(i * 19.5).toFixed(2)},"status":"PAID"}`, 'EX', '1800'])
  }
  for (let i = 1; i <= 80; i++) {
    cmds.push(['SET', `cache:page:${3000 + i}`, 'C'.repeat(256) + `-${i}`, 'EX', '43200'])
  }
  for (let i = 1; i <= 30; i++) {
    cmds.push(['SET', `session:tok:${4000 + i}`, `jwt-session-state-${i}`, 'EX', '172800'])
  }
  // 2. BigKeys for Analyzer spotlight
  cmds.push(['SET', 'big:report:monthly_2026_11', 'R'.repeat(65536)])
  cmds.push(['SET', 'big:snapshot:inventory_cache', 'I'.repeat(24576)])
  for (let f = 1; f <= 48; f++) {
    cmds.push(['HSET', 'big:catalog:sku_index', `sku_${String(f).padStart(3, '0')}`, `product-desc-payload-${f}`])
  }
  cmds.push(['RPUSH', 'queue:notify:pending', 'msg-1', 'msg-2', 'msg-3', 'msg-4', 'msg-5'])
  cmds.push(['SADD', 'tag:feature:enabled', 'zgc', 'epoll', 's3-fifo', 'sq8-hnsw', 'helm-v3'])
  cmds.push(['ZADD', 'rank:hot:products', '980', 'sku-101', '875', 'sku-205', '760', 'sku-309', '640', 'sku-412'])
  await sendRespPipeline(REDIS_PORT, cmds, 200)

  // 3. Vector HNSW collection (10 meaningful 8D vectors)
  const vecDocs = [
    ['doc-tiering-arch', [0.95, 0.18, 0.05, 0.02, 0.12, 0.04, 0.01, 0.03]],
    ['doc-lsm-compaction', [0.91, 0.25, 0.08, 0.04, 0.15, 0.05, 0.02, 0.01]],
    ['doc-wal-groupcommit', [0.87, 0.31, 0.12, 0.06, 0.18, 0.09, 0.04, 0.02]],
    ['doc-s3fifo-cache', [0.82, 0.36, 0.19, 0.09, 0.21, 0.11, 0.05, 0.03]],
    ['doc-hnsw-sq8-fma', [0.78, 0.42, 0.22, 0.11, 0.16, 0.14, 0.08, 0.05]],
    ['doc-raft-election', [0.22, 0.89, 0.15, 0.12, 0.07, 0.19, 0.05, 0.04]],
    ['doc-2pc-percolator', [0.18, 0.85, 0.24, 0.16, 0.09, 0.22, 0.08, 0.06]],
    ['doc-k8s-operator', [0.11, 0.21, 0.92, 0.18, 0.06, 0.14, 0.19, 0.09]],
    ['doc-helm-prometheus', [0.09, 0.17, 0.88, 0.26, 0.08, 0.16, 0.24, 0.12]],
    ['doc-backup-sha256', [0.14, 0.19, 0.74, 0.48, 0.12, 0.21, 0.15, 0.11]]
  ]
  for (const [id, values] of vecDocs) {
    await apiJson('/api/v1/multimodel/vector/add', token, {
      method: 'POST',
      body: JSON.stringify({ instanceId, collection: 'docs_emb_8d', id, values })
    })
  }

  // 4. TimeSeries 24 realistic points
  const baseTs = Date.now() - 24 * 60 * 1000
  const cpuWave = [31, 34, 38, 42, 49, 55, 61, 58, 52, 46, 44, 48, 53, 62, 67, 64, 59, 51, 45, 43, 47, 52, 49, 44]
  for (let i = 0; i < cpuWave.length; i++) {
    await apiJson('/api/v1/multimodel/timeseries/add', token, {
      method: 'POST',
      body: JSON.stringify({
        instanceId,
        key: 'ts:cpu:node-01',
        timestamp: baseTs + i * 60 * 1000,
        value: cpuWave[i]
      })
    })
  }

  // 5. JSON document
  await apiJson('/api/v1/multimodel/json/set', token, {
    method: 'POST',
    body: JSON.stringify({
      instanceId,
      key: 'doc:service:tiering-kv',
      path: '$',
      value: JSON.stringify({
        service: 'tiering-kv',
        version: 'v6.4.0',
        transport: 'linux-native-epoll',
        gc: 'generational-zgc',
        tiering: { lowWatermark: 0.7, highWatermark: 0.85, evictionPolicy: 'sampled-lfu' },
        features: ['sha256-backup', 'dry-run-restore', 'bigkey-analyzer', 'prometheus-metrics', 'helm-v3']
      })
    })
  })

  // 6. RBAC users (OPS & VIEWER)
  for (const u of [
    { username: 'ops_wang', password: 'opsPassword123', role: 'OPS' },
    { username: 'viewer_li', password: 'viewPassword123', role: 'VIEWER' }
  ]) {
    await apiJson('/api/v1/users', token, { method: 'POST', body: JSON.stringify(u) })
  }

  // 7. Alert rules + HMAC Webhook channel
  await apiJson('/api/v1/alerts/rules', token, {
    method: 'POST',
    body: JSON.stringify({
      name: '热层内存高水位告警 (≥85%)',
      instanceId,
      metric: 'MEMORY_RATIO',
      operator: 'GT',
      threshold: 0.85,
      durationSeconds: 60,
      level: 'WARN',
      enabled: true
    })
  })
  await apiJson('/api/v1/alerts/rules', token, {
    method: 'POST',
    body: JSON.stringify({
      name: '实例键数观测阈值 (>100 键演示)',
      instanceId,
      metric: 'TOTAL_KEYS',
      operator: 'GT',
      threshold: 100,
      durationSeconds: 0,
      level: 'WARN',
      enabled: true
    })
  })
  await apiJson('/api/v1/alerts/channels', token, {
    method: 'POST',
    body: JSON.stringify({
      name: 'OPS-运维告警 Webhook',
      type: 'WEBHOOK',
      url: 'https://ops-hooks.example.internal/tiering-kv/alerts',
      secret: 'demo-hmac-sha256-signing-secret',
      silenceSeconds: 300,
      enabled: true
    })
  })

  // 8. Cron Backup Schedule + 2 verified Backups + physical BGSAVE Checkpoint
  await apiJson('/api/v1/backups/schedules', token, {
    method: 'POST',
    body: JSON.stringify({
      instanceId,
      name: '每日凌晨逻辑全量备份',
      cronExpression: '0 2 * * *',
      mode: 'LOGICAL',
      retentionCount: 7,
      enabled: true
    })
  })
  const b1 = await apiJson(`/api/v1/backups?instanceId=${instanceId}`, token, {
    method: 'POST',
    headers: { 'X-Request-Id': 'req-v640-backup-01' }
  })
  const backupId = b1.json?.data?.id
  for (let i = 0; i < 40; i++) {
    const list = await apiJson(`/api/v1/backups?instanceId=${instanceId}`, token)
    const rec = list.json?.data?.find((x) => x.id === backupId)
    if (rec?.status === 'SUCCESS') break
    await sleep(300)
  }
  await apiJson(`/api/v1/backups/${backupId}/verify`, token, {
    method: 'POST',
    headers: { 'X-Request-Id': 'req-v640-verify-01' }
  })
  await apiJson(`/api/v1/backups/checkpoint?instanceId=${instanceId}`, token, {
    method: 'POST',
    headers: { 'X-Request-Id': 'req-v640-bgsave-01' }
  })

  // Mutate 3 keys & delete 2 keys so Dry-Run restore wizard displays both conflicts and new keys
  await sendRespPipeline(
    REDIS_PORT,
    [
      ['SET', 'cache:page:3001', 'modified-after-snapshot'],
      ['SET', 'cache:page:3002', 'modified-after-snapshot'],
      ['SET', 'cache:page:3003', 'modified-after-snapshot'],
      ['DEL', 'cache:page:3004'],
      ['DEL', 'cache:page:3005']
    ],
    10
  )

  // 9. Generate 30s live traffic so Dashboard ECharts trends look rich & realistic + alert fires
  console.log('[E-seed] Running 30s live burst traffic to populate Dashboard trend charts...')
  const trafficEnd = Date.now() + 30000
  let round = 0
  while (Date.now() < trafficEnd) {
    round++
    const burst = []
    for (let i = 0; i < 200; i++) {
      burst.push(['GET', `cache:page:${3010 + (i % 60)}`])
      if (i % 4 === 0) burst.push(['SET', `traffic:live:${i % 40}`, `tick-${round}-${i}`, 'EX', '300'])
    }
    await sendRespPipeline(REDIS_PORT, burst, 200)
    await sleep(350)
  }

  return { backupId }
}

async function captureAll() {
  const loginRes = await apiJson('/api/v1/auth/login', null, {
    method: 'POST',
    headers: { 'X-Request-Id': 'req-v640-login-admin' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' })
  })
  const token = loginRes.json.data.token
  const instRes = await apiJson('/api/v1/instances', token)
  const instanceId = instRes.json.data[0].id

  if (!SKIP_SEED) {
    await seedRichData(token, instanceId)
  }

  const require = createRequire(import.meta.url)
  const { chromium } = require(resolve('console-web/node_modules/@playwright/test'))
  const browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 2
  })
  const page = await context.newPage()

  async function shot(fileName, fullPage = false) {
    await sleep(700)
    const dst = resolve(FIG_DIR, fileName)
    await page.screenshot({ path: dst, fullPage })
    console.log(`  [SHOT] ${fileName}`)
  }

  // 1. LoginView
  await page.goto(`${BASE}/#/login`, { waitUntil: 'networkidle' })
  await page.getByTestId('login-submit').waitFor({ state: 'visible' })
  await shot('ui-login.png')

  // Sign in
  await page.getByPlaceholder('admin').fill('admin')
  await page.getByPlaceholder('请输入密码').fill('admin123')
  await page.getByTestId('login-submit').click()
  await page.waitForURL(/#\/dashboard/)
  await page.waitForLoadState('networkidle')
  await sleep(1200)

  // 2. DashboardView
  await shot('ui-dashboard.png')

  // 3. TopologyView
  await page.locator('.sidebar').getByText('集群拓扑').click()
  await page.waitForURL(/#\/topology/)
  await page.waitForLoadState('networkidle')
  await shot('ui-topology.png')

  // 4. InstancesView
  await page.locator('.sidebar').getByText('实例管理').click()
  await page.waitForURL(/#\/instances/)
  await page.waitForLoadState('networkidle')
  await shot('ui-instances.png')

  // 5. KeyspaceView (SCAN browse + open detail drawer for a Hash key)
  await page.locator('.sidebar').getByText('键空间').click()
  await page.waitForURL(/#\/keyspace/)
  await page.waitForLoadState('networkidle')
  await sleep(500)
  await shot('ui-keyspace.png')

  // 6. KeyspaceView -> BigKey / Prefix / TTL Analyzer tab
  await page.getByRole('tab', { name: '大键 / 前缀 / TTL 诊断' }).click()
  const analyzePane = page.getByRole('tabpanel', { name: '大键 / 前缀 / TTL 诊断' })
  await analyzePane.getByRole('button', { name: /运行诊断/ }).click()
  await analyzePane.getByText('Top 命名空间前缀分布').waitFor({ state: 'visible', timeout: 15000 })
  await sleep(1000)
  await shot('ui-keyspace-analyzer.png', true)

  // 7. MultiModelView -> Vector HNSW search
  await page.locator('.sidebar').getByText('多模型工作台').click()
  await page.waitForURL(/#\/multimodel/)
  await page.waitForLoadState('networkidle')
  await page.getByRole('button', { name: 'docs_emb_8d' }).first().click()
  await page
    .getByPlaceholder('输入查询向量，例如 0.1, 0.2, 0.3, 0.4')
    .fill('0.94, 0.20, 0.06, 0.03, 0.13, 0.05, 0.02, 0.02')
  await page.getByRole('button', { name: '检索', exact: true }).click()
  await page.locator('.el-table__row', { hasText: 'doc-tiering-arch' }).first().waitFor({ state: 'visible', timeout: 10000 })
  await shot('ui-multimodel-vector.png')

  // 8. MultiModelView -> TimeSeries ECharts
  await page.getByRole('tab', { name: 'TimeSeries 时序分析' }).click()
  const tsPane = page.locator('#pane-timeseries')
  await tsPane.getByPlaceholder('时序键名').fill('ts:cpu:node-01')
  await tsPane.getByRole('button', { name: '查询', exact: true }).click()
  await tsPane.locator('canvas').waitFor({ state: 'visible', timeout: 10000 })
  await sleep(800)
  await shot('ui-multimodel-ts-json.png')

  // 9. TerminalView (execute safe commands + blocked dangerous command)
  await page.locator('.sidebar').getByText('在线终端').click()
  await page.waitForURL(/#\/terminal/)
  await page.waitForLoadState('networkidle')
  const cmdInput = page.locator('.command-input').first()
  for (const cmd of ['PING', 'DBSIZE', 'INFO tiering', 'FLUSHALL']) {
    await cmdInput.fill(cmd)
    await page.locator('.send').first().click()
    await sleep(500)
  }
  await shot('ui-terminal.png')

  // 10. AlertsView
  await page.locator('.sidebar').getByText('告警中心').click()
  await page.waitForURL(/#\/alerts/)
  await page.waitForLoadState('networkidle')
  await shot('ui-alerts.png')

  // 11. SlowLogView
  await page.locator('.sidebar').getByText('慢查询日志').click()
  await page.waitForURL(/#\/slowlog/)
  await page.waitForLoadState('networkidle')
  await shot('ui-slowlog.png')

  // 12. BackupsView (main table with SHA-256 badges + Cron schedule + Checkpoint strip)
  await page.locator('.sidebar').getByText('备份恢复').click()
  await page.waitForURL(/#\/backups/)
  await page.waitForLoadState('networkidle')
  await shot('ui-backups.png', true)

  // 13. BackupsView -> Safe Restore Wizard (Dry-Run conflict preview dialog)
  const firstBackupRow = page.locator('.el-table__row', { hasText: '已校验' }).first()
  await firstBackupRow.getByRole('button', { name: '恢复', exact: true }).click()
  const wizard = page.getByRole('dialog', { name: /安全恢复向导/ })
  await wizard.waitFor({ state: 'visible', timeout: 10000 })
  await wizard.getByText('覆盖冲突键').waitFor({ state: 'visible', timeout: 15000 })
  await sleep(700)
  await shot('ui-backups-restore-wizard.png')
  await wizard.getByRole('button', { name: '取消' }).click()

  // 14. AuditView (with Request ID column)
  await page.locator('.sidebar').getByText('审计日志').click()
  await page.waitForURL(/#\/audit/)
  await page.waitForLoadState('networkidle')
  await shot('ui-audit.png')

  // 15. SystemView (INFO multi-section inspector)
  await page.locator('.sidebar').getByText('系统信息').click()
  await page.waitForURL(/#\/system/)
  await page.waitForLoadState('networkidle')
  await shot('ui-system.png')

  // 16. UsersView (RBAC)
  await page.locator('.sidebar').getByText('用户管理').click()
  await page.waitForURL(/#\/users/)
  await page.waitForLoadState('networkidle')
  await shot('ui-users.png')

  await browser.close()

  // Sync the 6 README showcase screenshots
  const readmeMap = [
    ['ui-login.png', '01-login.png'],
    ['ui-dashboard.png', '02-dashboard.png'],
    ['ui-keyspace.png', '03-keyspace.png'],
    ['ui-terminal.png', '04-terminal.png'],
    ['ui-alerts.png', '05-alerts.png'],
    ['ui-instances.png', '06-instances.png']
  ]
  for (const [src, dst] of readmeMap) {
    copyFileSync(resolve(FIG_DIR, src), resolve(README_SHOT_DIR, dst))
  }
  console.log('[DONE] All 16 HiDPI UI screenshots captured and README screenshots refreshed!')
}

captureAll().catch((err) => {
  console.error(err)
  process.exit(1)
})
