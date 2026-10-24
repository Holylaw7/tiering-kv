// 控制台端到端冒烟：登录 → 仪表盘 → 键空间 → 终端，并输出截图
// 用法：node scripts/smoke.mjs   （需先启动 console-server 与 vite dev，且安装 playwright）
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'

const BASE = process.env.SMOKE_BASE || 'http://127.0.0.1:5173'
const OUT = process.env.SMOKE_OUT || 'screenshots'
const USER = process.env.SMOKE_USER || 'admin'
const PASS = process.env.SMOKE_PASS || 'admin123'

mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
const failures = []

page.on('console', (msg) => {
  if (msg.type() === 'error') failures.push('console: ' + msg.text())
})
page.on('pageerror', (err) => failures.push('pageerror: ' + err.message))

async function shot(name) {
  await page.screenshot({ path: `${OUT}/${name}.png`, fullPage: false })
  console.log('shot ->', `${OUT}/${name}.png`)
}

await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await shot('01-login')

await page.fill('input[autocomplete="username"]', USER)
await page.fill('input[autocomplete="current-password"]', PASS)
await page.click('button:has-text("登 录")')
await page.waitForURL((url) => url.hash.includes('/dashboard'), { timeout: 15000 })
await page.waitForTimeout(2500)
await shot('02-dashboard')

await page.goto(BASE + '/#/keyspace', { waitUntil: 'networkidle' })
await page.waitForTimeout(1500)
await shot('03-keyspace')

await page.goto(BASE + '/#/terminal', { waitUntil: 'networkidle' })
await page.fill('input[placeholder="输入命令后回车执行"]', 'INFO stats')
await page.press('input[placeholder="输入命令后回车执行"]', 'Enter')
await page.waitForTimeout(1200)
await shot('04-terminal')

await page.goto(BASE + '/#/alerts', { waitUntil: 'networkidle' })
await page.waitForTimeout(1200)
await shot('05-alerts')

await page.goto(BASE + '/#/instances', { waitUntil: 'networkidle' })
await page.waitForTimeout(1000)
await shot('06-instances')

const bodyText = await page.textContent('body')
for (const expected of ['Tiering-KV', '实例管理']) {
  if (!bodyText || !bodyText.includes(expected)) {
    failures.push('缺少预期文本: ' + expected)
  }
}

await browser.close()

if (failures.length) {
  console.error('冒烟失败:\n' + failures.join('\n'))
  process.exit(1)
}
console.log('冒烟通过')
