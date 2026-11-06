import { chromium } from 'playwright'
import { resolve } from 'node:path'
const BASE = 'http://127.0.0.1:5173'
const OUT = resolve('../docs/screenshots/v2')
const browser = await chromium.launch()
const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN', permissions: ['clipboard-read', 'clipboard-write'] })
const page = await ctx.newPage()
await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await page.fill('input[autocomplete="username"]', 'admin')
await page.fill('input[autocomplete="current-password"]', 'admin123')
await page.click('[data-testid="login-submit"]')
await page.waitForURL(/dashboard/)
await page.waitForTimeout(1800)
await page.locator('.instance-select').click()
await page.waitForTimeout(400)
await page.locator('.el-select-dropdown__item', { hasText: 'demo-tiering' }).first().click()
await page.waitForTimeout(3000)
await page.locator('.link-btn').first().click()
await page.waitForTimeout(1800)
await page.screenshot({ path: resolve(OUT, '28-sstable-hardening.png') })

const badge = await page.locator('.order-badge').innerText()
const rows = await page.locator('.el-drawer .el-table__row').count()
const firstRow = await page.locator('.el-drawer .el-table__row').first().innerText()

// 排序：点"大小"表头两次（升/降）
await page.locator('.el-drawer th', { hasText: '大小' }).click()
await page.waitForTimeout(600)
const ascFirst = await page.locator('.el-drawer .el-table__row').first().innerText()
await page.locator('.el-drawer th', { hasText: '大小' }).click()
await page.waitForTimeout(600)
const descFirst = await page.locator('.el-drawer .el-table__row').first().innerText()

// 复制原始 Key
await page.locator('.el-drawer .link-btn').first().click()
await page.waitForTimeout(500)
const clip = await page.evaluate(() => navigator.clipboard.readText())

console.log(JSON.stringify({
  badge, rows,
  firstRow: firstRow.replace(/\s+/g, ' ').slice(0, 90),
  sortAsc: ascFirst.replace(/\s+/g, ' ').slice(0, 40),
  sortDesc: descFirst.replace(/\s+/g, ' ').slice(0, 40),
  clipboard: clip.slice(0, 80),
}, null, 2))
await browser.close()
