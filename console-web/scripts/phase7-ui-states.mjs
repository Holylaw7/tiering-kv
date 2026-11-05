import { chromium } from 'playwright'
import { resolve } from 'node:path'

const BASE = 'http://127.0.0.1:5173'
const OUT = resolve('../docs/screenshots/v2')
const browser = await chromium.launch()
const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN' })
const page = await ctx.newPage()

await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await page.fill('input[autocomplete="username"]', 'admin')
await page.fill('input[autocomplete="current-password"]', 'admin123')
await page.click('[data-testid="login-submit"]')
await page.waitForURL(/dashboard/)
await page.waitForTimeout(1500)

async function select(name) {
  await page.locator('.instance-select').click()
  await page.waitForTimeout(400)
  await page.locator('.el-select-dropdown__item', { hasText: name }).first().click()
  await page.waitForTimeout(3000)
}

// 正常态：demo-tiering
await select('demo-tiering')
await page.screenshot({ path: resolve(OUT, '24-state-normal.png') })
const normal = await page.locator('.tiering').innerText()

// 零值态：phase7-empty（空实例，有 Tiering 段但全部为 0）
const zeroOption = page.locator('.el-select-dropdown__item', { hasText: 'phase7-empty' })
await page.locator('.instance-select').click()
await page.waitForTimeout(400)
if (await zeroOption.count()) {
  await zeroOption.first().click()
  await page.waitForTimeout(3000)
  await page.screenshot({ path: resolve(OUT, '25-state-zero.png') })
}
const zero = await page.locator('.tiering').innerText().catch(() => '')

console.log(JSON.stringify({
  normal: normal.replace(/\n+/g, ' | ').slice(0, 200),
  zero: zero.replace(/\n+/g, ' | ').slice(0, 200)
}, null, 2))
await browser.close()
