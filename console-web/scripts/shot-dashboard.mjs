import { chromium } from 'playwright'
const BASE = process.env.SMOKE_BASE || 'http://127.0.0.1:4173'
const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await page.fill('input[autocomplete="username"]', 'admin')
await page.fill('input[autocomplete="current-password"]', 'admin123')
await page.click('button:has-text("登 录")')
await page.waitForURL(u => u.hash.includes('/dashboard'))
await page.waitForTimeout(4000)
await page.screenshot({ path: 'screenshots/02-dashboard.png' })
console.log('done')
await browser.close()
