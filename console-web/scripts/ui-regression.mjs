#!/usr/bin/env node
/**
 * 视觉重构后的功能回归（TD-094 Phase 5）：
 * 登录 → 路由可达 → 终端执行 → 键空间扫描 → 审计加载 → 实例切换控件存在。
 */
import { chromium } from 'playwright'

const BASE = process.argv[2] || 'http://127.0.0.1:5173'
const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log((ok ? 'PASS ' : 'FAIL ') + name + (detail ? ' — ' + detail : ''))
}

const browser = await chromium.launch()
const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' })
const page = await context.newPage()
const errors = []
page.on('console', (msg) => { if (msg.type() === 'error') errors.push(msg.text()) })
page.on('pageerror', (err) => errors.push(String(err)))

await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await page.fill('input[autocomplete="username"]', 'admin')
await page.fill('input[autocomplete="current-password"]', 'admin123')
await page.click('[data-testid="login-submit"]')
await page.waitForURL(/dashboard/, { timeout: 20000 })
await page.waitForTimeout(1500)
check('登录并进入运行总览', page.url().includes('dashboard'))

const metrics = await page.locator('.metric').count()
check('运行总览指标带渲染', metrics === 6, '指标数=' + metrics)
const bars = await page.locator('.bar-row').count()
check('存储水位条渲染', bars === 2, '进度条=' + bars)

await page.goto(BASE + '/#/terminal', { waitUntil: 'networkidle' })
await page.waitForTimeout(600)
await page.fill('.command-input', 'PING')
await page.keyboard.press('Enter')
await page.waitForTimeout(1200)
const termText = await page.locator('.term-body').innerText()
check('终端执行 PING 返回 PONG', termText.includes('PONG'), termText.replace(/\n/g, ' ').slice(0, 60))
const histCount = await page.locator('.history-item').count()
check('终端历史列表渲染', histCount > 0, '条数=' + histCount)

await page.goto(BASE + '/#/keyspace', { waitUntil: 'networkidle' })
await page.waitForTimeout(1200)
const keyRows = await page.locator('.el-table__body tr').count()
check('键空间扫描出数据', keyRows > 0, '行数=' + keyRows)

await page.goto(BASE + '/#/audit', { waitUntil: 'networkidle' })
await page.waitForTimeout(1200)
const auditRows = await page.locator('.el-table__body tr').count()
check('审计日志加载', auditRows > 0, '行数=' + auditRows)

await page.goto(BASE + '/#/instances', { waitUntil: 'networkidle' })
await page.waitForTimeout(800)
const instanceRows = await page.locator('.el-table__body tr').count()
check('实例列表渲染', instanceRows > 0, '行数=' + instanceRows)

await page.goto(BASE + '/#/system', { waitUntil: 'networkidle' })
await page.waitForTimeout(1000)
const sysText = await page.locator('.page').innerText()
check('系统信息读取 INFO', sysText.includes('standalone') || sysText.includes('5.1.0'))

const selectBox = await page.locator('.instance-select').count()
check('实例切换控件存在', selectBox === 1)

check('无前端运行时错误', errors.length === 0, errors.slice(0, 2).join(' | '))

await browser.close()
const failed = results.filter((r) => !r.ok)
console.log('\n总结：' + results.length + ' 项，通过 ' + (results.length - failed.length) + '，失败 ' + failed.length)
process.exit(failed.length === 0 ? 0 : 1)
