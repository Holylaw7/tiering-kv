import fs from 'node:fs'
const OUT = process.argv[2]
const INK = '#202124', MUTED = '#626B78', FAINT = '#9299A3', LINE = '#E5E7EB'
const ACCENT = '#3B5BDB', ACCENT_SOFT = '#EEF2FF', COLD = '#6FA88A', COLD_SOFT = '#EFF6F1'
const FONT = "'Segoe UI','Microsoft YaHei',sans-serif"
const esc = (s) => String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')
const box = (x,y,w,h,o={}) => `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${o.rx??10}" fill="${o.fill??'#FFF'}" stroke="${o.stroke??LINE}" stroke-width="1"/>`
const txt = (x,y,s,o={}) => `<text x="${x}" y="${y}" font-family="${FONT}" font-size="${o.size??13}" fill="${o.fill??INK}" font-weight="${o.weight??400}" text-anchor="${o.anchor??'start'}">${esc(s)}</text>`
const arrow = (x1,y1,x2,y2) => `<defs><marker id="a${x1}${y1}" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto"><path d="M0,0 L6,3 L0,6 z" fill="#9AA4B2"/></marker></defs><line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="#9AA4B2" stroke-width="1.4" marker-end="url(#a${x1}${y1})"/>`
const W = 900, H = 620
let s = ''
const stages = [
  { t: '传统持久化存储', d: '磁盘索引结构 · 日志结构存储', y: 60 },
  { t: 'LSM-Tree 与键值存储引擎', d: '写入优化 · 合并策略 · 缓存与布隆过滤器', y: 165 },
  { t: '冷热分层与异构存储', d: '热度识别 · 数据放置 · 迁移与回源', y: 270 },
  { t: '分布式与云原生演进', d: '复制与一致性 · 资源解耦 · 管理控制面', y: 375 },
]
for (const st of stages) {
  s += box(150, st.y, 600, 66, { fill: '#FFFFFF' })
  s += txt(450, st.y + 28, st.t, { size: 14, weight: 600, anchor: 'middle' })
  s += txt(450, st.y + 50, st.d, { size: 11.5, fill: MUTED, anchor: 'middle' })
  s += arrow(450, st.y + 66, 450, st.y + 99)
}
s += box(150, 482, 600, 78, { fill: COLD_SOFT, stroke: '#BFD8C9', rx: 10 })
s += txt(450, 510, 'Tiering-KV 的研究定位', { size: 14, weight: 600, anchor: 'middle', fill: '#2F6B4F' })
s += txt(450, 536, '把 Redis 协议兼容、LSM 存储、冷热分层与独立控制面整合为可验证的工程系统', { size: 11.5, fill: MUTED, anchor: 'middle' })
s += txt(24, 592, '图 1-1　研究脉络与本文定位（工程整合与验证，而非提出新的存储理论）', { size: 11, fill: FAINT })
const svgOut = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}"><rect width="${W}" height="${H}" fill="#FFFFFF"/>${s}</svg>`
fs.writeFileSync(OUT + '/fig10-research-lineage.svg', svgOut)
console.log('svg written')
