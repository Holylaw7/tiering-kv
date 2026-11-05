import { chromium } from 'playwright'
import { resolve } from 'node:path'

const BASE = 'http://127.0.0.1:5173'
const OUT = resolve('../docs/screenshots')
const browser = await chromium.launch()
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' })
const page = await ctx.newPage()

await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await page.waitForTimeout(600)
const htmlClass = await page.evaluate(() => document.documentElement.className)
const bg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor)
const panelBg = await page.evaluate(() => {
  const el = document.querySelector('.panel') || document.querySelector('.metrics')
  return el ? getComputedStyle(el).backgroundColor : 'n/a'
})
console.log('html.class =', JSON.stringify(htmlClass))
console.log('body.background =', bg)
console.log('panel/metrics background =', panelBg)
await page.screenshot({ path: resolve(OUT, 'verify-login-fresh.png') })

await page.fill('input[autocomplete="username"]', 'admin')
await page.fill('input[autocomplete="current-password"]', 'admin123')
await page.click('[data-testid="login-submit"]')
await page.waitForURL(/dashboard/)
await page.waitForTimeout(1500)
const accent = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--accent').trim())
const pageBg = await page.evaluate(() => getComputedStyle(document.querySelector('.main')).backgroundColor)
console.log('--accent =', accent, '| .main background =', pageBg)
await page.screenshot({ path: resolve(OUT, 'verify-dashboard-fresh.png') })
await browser.close()
