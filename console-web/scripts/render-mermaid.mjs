import { chromium } from 'playwright'
import fs from 'node:fs'
import path from 'node:path'

const DIR = process.argv[2]
const mermaidJs = path.resolve('node_modules/mermaid/dist/mermaid.min.js')
const mermaidSource = fs.readFileSync(mermaidJs, 'utf8')

const THEME = {
  theme: 'base',
  themeVariables: {
    fontFamily: "'Segoe UI','Microsoft YaHei',sans-serif",
    fontSize: '13px',
    primaryColor: '#FFFFFF',
    primaryBorderColor: '#CBD2D9',
    primaryTextColor: '#202124',
    lineColor: '#9AA4B2',
    secondaryColor: '#F7F8FA',
    tertiaryColor: '#FBFCFE',
    noteBkgColor: '#FBFCFE',
    noteBorderColor: '#E5E7EB',
    noteTextColor: '#626B78',
    actorBkg: '#FFFFFF',
    actorBorder: '#CBD2D9',
    actorTextColor: '#202124',
    signalColor: '#626B78',
    signalTextColor: '#202124',
    labelBoxBkgColor: '#EEF2FF',
    labelBoxBorderColor: '#C7D2F5',
    labelTextColor: '#3B5BDB',
  },
  class: { useMaxWidth: false },
  sequence: { useMaxWidth: false, wrap: false, diagramMarginX: 16, diagramMarginY: 12, actorMargin: 60, boxMargin: 8, messageMargin: 28 },
}

const browser = await chromium.launch()
const page = await browser.newPage({ deviceScaleFactor: 2 })
for (const file of fs.readdirSync(DIR).filter((f) => f.endsWith('.mmd'))) {
  const code = fs.readFileSync(path.join(DIR, file), 'utf8')
  await page.setContent('<html><body style="margin:0;background:#fff"><div id="c"></div></body></html>')
  await page.addScriptTag({ content: mermaidSource })
  const svg = await page.evaluate(async ({ code, theme }) => {
    window.mermaid.initialize({ startOnLoad: false, ...theme })
    const { svg } = await window.mermaid.render('g' + Date.now(), code)
    document.getElementById('c').innerHTML = svg
    return svg
  }, { code, theme: THEME })
  const svgFile = path.join(DIR, file.replace('.mmd', '.svg'))
  fs.writeFileSync(svgFile, svg)
  const box = await page.locator('#c svg').boundingBox()
  await page.setViewportSize({ width: Math.ceil(box.width) + 2, height: Math.ceil(box.height) + 2 })
  await page.waitForTimeout(200)
  const png = path.join(DIR, file.replace('.mmd', '.png'))
  await page.screenshot({ path: png, clip: { x: 0, y: 0, width: Math.ceil(box.width) + 2, height: Math.ceil(box.height) + 2 } })
  console.log('rendered', file, Math.round(box.width) + 'x' + Math.round(box.height))
}
await browser.close()
