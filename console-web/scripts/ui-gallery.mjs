#!/usr/bin/env node
/**
 * 全站视觉画廊（TD-094）：逐页 + 抽屉 + 弹窗 + 交互后状态，输出到 docs/screenshots/v2。
 * 用法（console-web 目录）：node scripts/ui-gallery.mjs
 */
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

const BASE = 'http://127.0.0.1:5173'
const OUT = resolve('../docs/screenshots/v2')
mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch()
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN', deviceScaleFactor: 1 })
const page = await ctx.newPage()

async function shot(name, opts = {}) {
  await page.waitForTimeout(opts.wait ?? 700)
  await page.screenshot({ path: resolve(OUT, name + '.png'), fullPage: opts.full ?? false })
  console.log('saved', name)
}

async function go(hash) {
  await page.goto(BASE + '/#/' + hash, { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
}

// ---------- 登录 ----------
await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await shot('01-login', { wait: 800 })

await page.fill('input[autocomplete="username"]', 'admin')
await page.fill('input[autocomplete="current-password"]', 'admin123')
await page.click('[data-testid="login-submit"]')
await page.waitForURL(/dashboard/, { timeout: 20000 })
await page.waitForTimeout(1800)

// ---------- 运行总览 ----------
await shot('02-dashboard-fold')
await shot('03-dashboard-full', { full: true, wait: 300 })

// ---------- 键空间 ----------
await go('keyspace')
await page.waitForTimeout(800)
await shot('04-keyspace-list')
const viewBtn = page.locator('.el-table__body tr').first().locator('button', { hasText: '查看' })
if (await viewBtn.count()) {
  await viewBtn.first().click()
  await page.waitForTimeout(1200)
  await shot('05-keyspace-detail')
  await page.keyboard.press('Escape')
  await page.waitForTimeout(400)
}

// ---------- 在线终端（执行两条命令） ----------
await go('terminal')
await page.fill('.command-input', 'INFO stats')
await page.keyboard.press('Enter')
await page.waitForTimeout(1200)
await page.fill('.command-input', 'SCAN 0 COUNT 5')
await page.keyboard.press('Enter')
await page.waitForTimeout(1200)
await shot('06-terminal-executed')

// ---------- 实例管理 ----------
await go('instances')
await shot('07-instances')
const addInstance = page.locator('button', { hasText: '纳管实例' })
if (await addInstance.count()) {
  await addInstance.first().click()
  await page.waitForTimeout(700)
  await shot('08-instances-dialog')
  await page.keyboard.press('Escape')
  await page.waitForTimeout(400)
}

// ---------- 告警中心 ----------
await go('alerts')
await shot('09-alerts-events')
const rulesTab = page.locator('.el-tabs__item', { hasText: '告警规则' })
if (await rulesTab.count()) {
  await rulesTab.first().click()
  await page.waitForTimeout(800)
  await shot('10-alerts-rules')
}

// ---------- 备份恢复（真实创建一次） ----------
await go('backups')
await shot('11-backups-empty')
const createBackup = page.locator('button', { hasText: '立即备份' })
if (await createBackup.count()) {
  await createBackup.first().click()
  await page.waitForTimeout(2500)
  await shot('12-backups-created')
}

// ---------- 审计日志 ----------
await go('audit')
await shot('13-audit')

// ---------- 系统信息 ----------
await go('system')
await shot('14-system')

// ---------- 用户管理 + 新建弹窗 ----------
await go('users')
await shot('15-users')
const newUser = page.locator('button', { hasText: '新建' })
if (await newUser.count()) {
  await newUser.first().click()
  await page.waitForTimeout(700)
  await shot('16-users-dialog')
  await page.keyboard.press('Escape')
  await page.waitForTimeout(400)
}

// ---------- 账户菜单 + 修改口令 ----------
await page.locator('.account').click()
await page.waitForTimeout(500)
await shot('17-account-menu', { wait: 300 })
const pwd = page.locator('.el-dropdown-menu__item', { hasText: '修改口令' })
if (await pwd.count()) {
  await pwd.first().click()
  await page.waitForTimeout(700)
  await shot('18-password-dialog')
  await page.keyboard.press('Escape')
}

// ---------- 1280 适配 ----------
const ctx2 = await browser.newContext({ viewport: { width: 1280, height: 800 }, locale: 'zh-CN' })
const p2 = await ctx2.newPage()
await p2.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await p2.fill('input[autocomplete="username"]', 'admin')
await p2.fill('input[autocomplete="current-password"]', 'admin123')
await p2.click('[data-testid="login-submit"]')
await p2.waitForURL(/dashboard/)
await p2.waitForTimeout(1800)
await p2.screenshot({ path: resolve(OUT, '19-dashboard-1280.png') })
await p2.goto(BASE + '/#/keyspace', { waitUntil: 'networkidle' })
await p2.waitForTimeout(1200)
await p2.screenshot({ path: resolve(OUT, '20-keyspace-1280.png') })
console.log('saved 19/20 (1280)')

await browser.close()
