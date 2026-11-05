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

// 切换到冷热分层演示实例
await page.locator('.instance-select').click()
await page.waitForTimeout(500)
await page.locator('.el-select-dropdown__item', { hasText: 'demo-tiering' }).first().click()
await page.waitForTimeout(3500)
await page.screenshot({ path: resolve(OUT, '21-dashboard-tiering.png') })

const text = await page.locator('.tiering').innerText()
console.log(text.replace(/\n+/g, ' | '))
await browser.close()
