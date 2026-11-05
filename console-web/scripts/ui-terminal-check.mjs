import { chromium } from 'playwright'
import { resolve } from 'node:path'
const BASE = 'http://127.0.0.1:5173'
const OUT = resolve('../docs/screenshots/v2')
const browser = await chromium.launch()
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' })
const page = await ctx.newPage()
await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await page.fill('input[autocomplete="username"]', 'admin')
await page.fill('input[autocomplete="current-password"]', 'admin123')
await page.click('[data-testid="login-submit"]')
await page.waitForURL(/dashboard/)
await page.waitForTimeout(1200)
await page.goto(BASE + '/#/terminal', { waitUntil: 'networkidle' })
await page.waitForTimeout(700)
for (const cmd of ['PING', 'INFO tiering', 'SCAN 0 COUNT 3', 'BADCMD foo']) {
  await page.fill('.command-input', cmd)
  await page.keyboard.press('Enter')
  await page.waitForTimeout(900)
}
await page.screenshot({ path: resolve(OUT, '22-terminal-session.png') })
await page.locator('.term-action').click()
await page.waitForTimeout(500)
const h = await page.locator('.term-body').evaluate((el) => getComputedStyle(el).height)
console.log('expanded height =', h)
await page.screenshot({ path: resolve(OUT, '23-terminal-expanded.png') })
await browser.close()
