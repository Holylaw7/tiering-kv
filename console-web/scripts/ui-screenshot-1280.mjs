import { chromium } from 'playwright'
import { resolve } from 'node:path'

const BASE = 'http://127.0.0.1:5173'
const OUT = resolve('../docs/screenshots')
const browser = await chromium.launch()
const context = await browser.newContext({ viewport: { width: 1280, height: 800 }, locale: 'zh-CN' })
const page = await context.newPage()

await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await page.waitForTimeout(700)
await page.screenshot({ path: resolve(OUT, 'login-1280.png') })

await page.fill('input[autocomplete="username"]', 'admin')
await page.fill('input[autocomplete="current-password"]', 'admin123')
await page.click('[data-testid="login-submit"]')
await page.waitForURL(/dashboard/)
await page.waitForTimeout(1600)
await page.screenshot({ path: resolve(OUT, 'dashboard-1280.png') })

await page.goto(BASE + '/#/keyspace', { waitUntil: 'networkidle' })
await page.waitForTimeout(1200)
await page.screenshot({ path: resolve(OUT, 'keyspace-1280.png') })

await browser.close()
console.log('saved 1280 screenshots')
