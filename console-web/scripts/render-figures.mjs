import { chromium } from 'playwright'
import fs from 'node:fs'
import path from 'node:path'

const DIR = process.argv[2]
const browser = await chromium.launch()
const page = await browser.newPage({ deviceScaleFactor: 2 })
for (const file of fs.readdirSync(DIR).filter((f) => f.endsWith('.svg'))) {
  const svg = fs.readFileSync(path.join(DIR, file), 'utf8')
  const m = /width="(\d+)" height="(\d+)"/.exec(svg)
  const w = Number(m[1]), h = Number(m[2])
  await page.setViewportSize({ width: w, height: h })
  await page.setContent('<html><body style="margin:0;background:#fff">' + svg + '</body></html>')
  await page.waitForTimeout(300)
  const out = path.join(DIR, file.replace('.svg', '.png'))
  await page.screenshot({ path: out, clip: { x: 0, y: 0, width: w, height: h } })
  console.log('rendered', file, w + 'x' + h)
}
await browser.close()
