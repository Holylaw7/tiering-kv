/**
 * Phase 10 UI 三组场景（Playwright）。
 * UI-1 在线终端：连续会话、顺序、无重复渲染
 * UI-2 状态页面：离线实例 DOWN + 指标 — + 红色提示；备份状态；告警触发/恢复
 * UI-3 权限与认证：VIEWER 越权提示；过期 Token 跳转登录
 *
 * 截图只是证据，PASS 由断言决定。
 */
import crypto from 'node:crypto'
import { chromium } from 'playwright'
import { resolve } from 'node:path'
import { api, check, ensureInstance, exitWith, login } from './lib.mjs'

const BASE = 'http://127.0.0.1:5173'
const OUT = resolve('../docs/screenshots/v2')
const results = []

function assert(name, ok, detail = '') {
  results.push({ name, ok })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' — ' + detail : ''}`)
}

const admin = await login('admin', 'admin123')
const token = admin.token

// 准备：离线实例 + 触发中的告警 + viewer 账号
const instances = await api('/instances', { token })
let offline = instances.json.data.find((i) => i.port === 6399)
if (!offline) {
  const created = await api('/instances', {
    method: 'POST', token,
    body: { name: 'phase10-ui-offline', host: '127.0.0.1', port: 6399, remark: 'UI 离线态', enabled: true }
  })
  offline = created.json.data
}
await api(`/instances/${offline.id}/probe`, { method: 'POST', token })

const testInstance = await ensureInstance(token)
const ruleName = 'phase10-ui-alert-' + Date.now()
const rule = await api('/alerts/rules', {
  method: 'POST', token,
  body: { name: ruleName, instanceId: testInstance.id, metric: 'total_keys', operator: 'LT', threshold: 1e12, durationSeconds: 0, level: 'WARN', enabled: true }
})
const ruleId = rule.json.data.id

const viewerName = 'phase10-viewer-' + Date.now().toString().slice(-6)
await api('/users', {
  method: 'POST', token,
  body: { username: viewerName, password: 'viewerPass!1', role: 'VIEWER', enabled: true }
})

const browser = await chromium.launch()
const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN' })
const page = await ctx.newPage()

async function uiLogin(username, password) {
  await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
  await page.fill('input[autocomplete="username"]', username)
  await page.fill('input[autocomplete="current-password"]', password)
  await page.click('[data-testid="login-submit"]')
  await page.waitForURL(/dashboard/)
  await page.waitForTimeout(1200)
}

async function selectInstance(name) {
  await page.locator('.instance-select').click()
  await page.waitForTimeout(400)
  await page.locator('.el-select-dropdown__item', { hasText: name }).first().click()
  await page.waitForTimeout(2500)
}

try {
  /* ---------------- UI-1 在线终端 ---------------- */
  await uiLogin('admin', 'admin123')
  await selectInstance('phase10-kernel')
  await page.goto(BASE + '/#/terminal', { waitUntil: 'networkidle' })
  await page.waitForTimeout(800)

  async function runCommand(command) {
    const before = await page.locator('.term-body .entry').count()
    await page.fill('.command-input', command)
    await page.press('.command-input', 'Enter')
    await page.waitForFunction(
      (n) => document.querySelectorAll('.term-body .entry').length > n,
      before, { timeout: 8000 }
    )
    return page.locator('.term-body .entry').last()
  }

  const first = await runCommand('PING')
  const firstText = await first.innerText()
  assert('终端执行 PING 显示 PONG', firstText.includes('PONG'), firstText.replace(/\s+/g, ' ').slice(0, 60))

  const second = await runCommand('SET phase10:ui:1 hello-ui')
  assert('终端 SET 返回 OK', (await second.innerText()).includes('OK'))

  const third = await runCommand('GET phase10:ui:1')
  const thirdText = await third.innerText()
  assert('终端 GET 读回写入值', thirdText.includes('hello-ui'), thirdText.replace(/\s+/g, ' ').slice(0, 60))

  const errEntry = await runCommand('NO_SUCH_COMMAND_UI')
  const errorReplies = await page.locator('.term-body .reply.error').count()
  assert('错误命令以错误样式渲染', errorReplies > 0, 'errorReplies=' + errorReplies)

  const entryCount = await page.locator('.term-body .entry').count()
  const commands = await page.locator('.term-body .entry .cmd .text').allInnerTexts()
  assert('会话保留全部 4 条命令（连续会话）', entryCount === 4 && commands.length === 4,
    'entries=' + entryCount + ' cmds=' + commands.join('|'))
  assert('命令按执行顺序排列',
    commands[0] === 'PING' && commands[1].startsWith('SET') && commands[2].startsWith('GET'),
    commands.join(' / '))
  assert('每条命令只渲染一次（无重复）',
    new Set(commands).size === commands.length, 'unique=' + new Set(commands).size)
  assert('终端标题显示实例名',
    (await page.locator('.term-title').innerText()).includes('phase10-kernel'))

  await page.screenshot({ path: resolve(OUT, 'ui-01-terminal.png') })

  /* ---------------- UI-2 状态与业务页面 ---------------- */
  await page.goto(BASE + '/#/dashboard', { waitUntil: 'networkidle' })
  await selectInstance('phase10-ui-offline')

  const topbar = await page.locator('.status-text, .conn-state, header').first().innerText()
  assert('离线实例顶部显示"连接失败"', topbar.includes('连接失败'), topbar.replace(/\s+/g, ' ').slice(0, 60))

  const banner = page.locator('.tiering-empty.is-error')
  assert('分层区显示红色不可达提示', await banner.count() > 0)

  const metricValues = await page.locator('.metric-value').allInnerTexts()
  assert('离线时指标显示 —（不是 0）',
    metricValues.filter((v) => v.trim() === '—').length >= 3,
    'values=' + metricValues.map((v) => v.trim()).join('|'))
  await page.screenshot({ path: resolve(OUT, 'ui-02-offline.png') })

  // 告警：等待事件触发后，页面应显示"触发中"
  await page.waitForTimeout(6000)
  await page.goto(BASE + '/#/alerts', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1500)
  const alertsText = await page.locator('.page').innerText()
  assert('告警页显示触发中的事件', alertsText.includes('触发中') || alertsText.includes('FIRING'),
    alertsText.replace(/\s+/g, ' ').slice(0, 80))

  // 备份页：展示现有记录状态
  await page.goto(BASE + '/#/backups', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1500)
  const backupsText = await page.locator('.page').innerText()
  assert('备份页显示记录状态（SUCCESS/FAILED）',
    backupsText.includes('SUCCESS') || backupsText.includes('FAILED'),
    backupsText.replace(/\s+/g, ' ').slice(0, 80))
  await page.screenshot({ path: resolve(OUT, 'ui-03-backups.png') })

  // 恢复告警，页面刷新后应显示"已恢复"
  const events = await api('/alerts/events?status=FIRING&page=1&size=50', { token })
  const event = events.json.data.items.find((e) => e.ruleId === ruleId)
  if (event) {
    await api(`/alerts/events/${event.id}/resolve`, { method: 'POST', token })
    // 默认列表按触发时间倒序展示全部事件，直接断言该行状态
    await page.goto(BASE + '/#/alerts', { waitUntil: 'networkidle' })
    await page.waitForTimeout(2000)
    const resolvedText = await page.locator('.page').innerText()
    assert('告警恢复后列表显示"已恢复"', resolvedText.includes('已恢复'),
      resolvedText.replace(/\s+/g, ' ').slice(0, 80))
    await page.screenshot({ path: resolve(OUT, 'ui-04-alerts.png') })
  } else {
    assert('告警恢复后列表显示"已恢复"', false, '未找到 FIRING 事件')
  }

  /* ---------------- UI-3 权限与认证 ---------------- */
  await page.evaluate(() => {
    localStorage.removeItem('tk_console_token')
    localStorage.removeItem('tk_console_user')
  })
  await uiLogin(viewerName, 'viewerPass!1')
  await page.goto(BASE + '/#/audit', { waitUntil: 'networkidle' })
  await page.waitForTimeout(2000)
  const toast = await page.locator('.el-message').allInnerTexts()
  const toastText = toast.join(' | ')
  assert('VIEWER 访问审计出现权限错误提示',
    /权限|403|Forbidden/.test(toastText), toastText.slice(0, 80))
  await page.screenshot({ path: resolve(OUT, 'ui-05-forbidden.png') })

  // 过期 Token：自签一个 exp 已过期的 token，写入 localStorage 后刷新
  const secret = 'tiering-kv-console-dev-secret-key-change-me-please-0123456789'
  const enc = (obj) => Buffer.from(JSON.stringify(obj)).toString('base64')
    .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_')
  const now = Math.floor(Date.now() / 1000)
  const head = enc({ alg: 'HS256', typ: 'JWT' })
  const body = enc({ sub: 'admin', uid: 1, role: 'ADMIN', iat: now - 7200, exp: now - 3600 })
  const sig = crypto.createHmac('sha256', secret).update(head + '.' + body).digest('base64')
    .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_')
  await page.evaluate((expired) => {
    localStorage.setItem('tk_console_token', expired)
  }, head + '.' + body + '.' + sig)

  await page.goto(BASE + '/#/dashboard', { waitUntil: 'networkidle' })
  await page.waitForTimeout(2500)
  assert('过期 Token 触发跳转登录页', page.url().includes('#/login'), 'url=' + page.url())
  assert('登录页表单可用', await page.locator('[data-testid="login-submit"]').count() > 0)
  await page.screenshot({ path: resolve(OUT, 'ui-06-login-redirect.png') })
} finally {
  await browser.close()
  // 清理：告警规则 + viewer 账号 + 离线实例
  await api(`/alerts/rules/${ruleId}`, { method: 'DELETE', token }).catch(() => null)
  const users = await api('/users', { token }).catch(() => null)
  const viewer = users?.json?.data?.find?.((u) => u.username === viewerName)
  if (viewer) await api(`/users/${viewer.id}`, { method: 'DELETE', token }).catch(() => null)
  await api(`/instances/${offline.id}`, { method: 'DELETE', token }).catch(() => null)
}

const failed = results.filter((r) => !r.ok)
console.log(`\n[UI 三组场景] 总结：${results.length} 项，通过 ${results.length - failed.length}，失败 ${failed.length}`)
setTimeout(() => process.exit(failed.length === 0 ? 0 : 1), 150)