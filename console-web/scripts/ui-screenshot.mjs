#!/usr/bin/env node
/**
 * UI 截图脚本（TD-093）：登录后逐页截图，用于人工核对视觉与回归对比。
 *
 * 用法（在 console-web 目录下）：node scripts/ui-screenshot.mjs
 */
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

const argv = process.argv.slice(2)
function arg(name, fallback) {
  const i = argv.indexOf('--' + name)
  return i >= 0 && argv[i + 1] !== undefined ? argv[i + 1] : fallback
}

const BASE = arg('base', 'http://127.0.0.1:5173')
const OUT = resolve(arg('out', '../docs/screenshots'))
const USER = arg('user', 'admin')
const PASS = arg('pass', 'admin123')

const PAGES = [
  ['dashboard', '/#/dashboard'],
  ['instances', '/#/instances'],
  ['keyspace', '/#/keyspace'],
  ['terminal', '/#/terminal'],
  ['alerts', '/#/alerts'],
  ['backups', '/#/backups'],
  ['audit', '/#/audit'],
  ['system', '/#/system'],
  ['users', '/#/users']
]

mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch()
const context = await browser.newContext({
  viewport: { width: 1440, height: 900 },
  deviceScaleFactor: 1,
  locale: 'zh-CN'
})
const page = await context.newPage()

await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await page.waitForTimeout(700)
await page.screenshot({ path: resolve(OUT, 'login.png') })
console.log('saved login.png')

await page.fill('input[autocomplete="username"]', USER)
await page.fill('input[autocomplete="current-password"]', PASS)
await page.click('[data-testid="login-submit"]')
await page.waitForURL(/dashboard/, { timeout: 20000 })
await page.waitForTimeout(1500)

for (const [name, path] of PAGES) {
  await page.goto(BASE + path, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1100)
  const file = resolve(OUT, name + '.png')
  await page.screenshot({ path: file })
  console.log('saved', file)
}

await browser.close()
