import fs from 'node:fs'
import path from 'node:path'

const OUT = process.argv[2]
const INK = '#202124'
const MUTED = '#626B78'
const FAINT = '#9299A3'
const LINE = '#E5E7EB'
const ACCENT = '#3B5BDB'
const ACCENT_SOFT = '#EEF2FF'
const COLD = '#6FA88A'
const COLD_SOFT = '#EFF6F1'
const WARN = '#B45309'
const WARN_SOFT = '#FDF4E7'
const DANGER = '#C62828'
const DANGER_SOFT = '#FDF0F0'
const FONT = "'Segoe UI','Microsoft YaHei',sans-serif"
const MONO = "'Cascadia Mono','Consolas',monospace"

const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

function box(x, y, w, h, opts = {}) {
  const fill = opts.fill ?? '#FFFFFF'
  const stroke = opts.stroke ?? LINE
  const dash = opts.dash ? `stroke-dasharray="${opts.dash}"` : ''
  return `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${opts.rx ?? 8}" fill="${fill}" stroke="${stroke}" stroke-width="1" ${dash}/>`
}

function text(x, y, s, opts = {}) {
  return `<text x="${x}" y="${y}" font-family="${opts.mono ? MONO : FONT}" font-size="${opts.size ?? 12}" fill="${opts.fill ?? INK}" font-weight="${opts.weight ?? 400}" text-anchor="${opts.anchor ?? 'start'}">${esc(s)}</text>`
}

function line(x1, y1, x2, y2, opts = {}) {
  const dash = opts.dash ? `stroke-dasharray="${opts.dash}"` : ''
  return `<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="${opts.stroke ?? '#CBD2D9'}" stroke-width="${opts.width ?? 1}" ${dash}/>`
}

function arrow(x1, y1, x2, y2, opts = {}) {
  const id = 'mk' + Math.random().toString(36).slice(2, 8)
  return `<defs><marker id="${id}" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto"><path d="M0,0 L6,3 L0,6 z" fill="${opts.stroke ?? '#9AA4B2'}"/></marker></defs>` +
    `<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="${opts.stroke ?? '#9AA4B2'}" stroke-width="${opts.width ?? 1.2}" marker-end="url(#${id})" ${opts.dash ? `stroke-dasharray="${opts.dash}"` : ''}/>`
}

function pill(x, y, label, opts = {}) {
  const w = opts.w ?? (label.length * 7.2 + 16)
  return box(x, y, w, 20, { fill: opts.fill ?? ACCENT_SOFT, stroke: opts.stroke ?? '#C7D2F5', rx: 10 }) +
    text(x + w / 2, y + 14, label, { size: 10.5, fill: opts.color ?? ACCENT, anchor: 'middle' })
}

/* ---------------- 图 1：总体架构 ---------------- */
function fig1() {
  const W = 1040, H = 620
  let s = ''
  s += box(24, 20, 992, 132, { fill: '#FBFCFE', stroke: LINE, rx: 10 })
  s += text(44, 46, '接入层', { size: 11, fill: FAINT, weight: 600 })
  const clients = [['管理员浏览器', 'Vue 3 控制台'], ['redis-cli / SDK', 'RESP2 / RESP3'], ['运维脚本 / CI', 'HTTP JSON']]
  clients.forEach(([a, b], i) => {
    const x = 44 + i * 250
    s += box(x, 62, 220, 70, { fill: '#FFFFFF' })
    s += text(x + 16, 88, a, { size: 13, weight: 600 })
    s += text(x + 16, 110, b, { size: 11, fill: MUTED })
  })

  s += box(24, 172, 480, 196, { fill: '#FFFFFF', stroke: ACCENT, dash: '1' })
  s += text(44, 198, '控制平面 · Spring Boot 3.5（console-server）', { size: 12.5, weight: 600, fill: ACCENT })
  const ctl = ['认证与 RBAC（JWT / ADMIN·OPS·VIEWER）', '实例纳管 · 探测 · RESP 连接池', '指标采集（5s）· 趋势 · 分层指标', '键空间 / 终端 / 告警 / 备份 / 审计']
  ctl.forEach((t, i) => {
    s += text(48, 228 + i * 30, '·', { size: 13, fill: ACCENT })
    s += text(62, 228 + i * 30, t, { size: 11.5, fill: MUTED })
  })
  s += box(44, 330, 440, 24, { fill: ACCENT_SOFT, stroke: '#C7D2F5', rx: 6 })
  s += text(264, 346, 'REST /api/v1 · OpenAPI 契约快照校验', { size: 10.5, fill: ACCENT, anchor: 'middle' })

  s += box(536, 172, 480, 196, { fill: '#FFFFFF', stroke: COLD })
  s += text(556, 198, '数据平面 · Netty 内核（tiering-kv）', { size: 12.5, weight: 600, fill: '#2F6B4F' })
  const core = ['RESP 编解码 · 命令层（157 个命令名）', '16 分片执行器 · 单连接有序', 'WAL → MemTable → SSTable → Compaction', 'Raft 复制 · 分布式事务 · 多模型']
  core.forEach((t, i) => {
    s += text(560, 228 + i * 30, '·', { size: 13, fill: COLD })
    s += text(574, 228 + i * 30, t, { size: 11.5, fill: MUTED })
  })
  s += box(556, 330, 440, 24, { fill: COLD_SOFT, stroke: '#BFD8C9', rx: 6 })
  s += text(776, 346, 'INFO · 分层指标 · SSTable 清单（按需）', { size: 10.5, fill: '#2F6B4F', anchor: 'middle' })

  s += arrow(264, 136, 264, 168)
  s += arrow(776, 136, 776, 168, { stroke: '#7FA890' })
  s += arrow(508, 270, 532, 270, { stroke: ACCENT, dash: '4 3' })

  s += box(24, 392, 300, 96, { fill: '#FFFFFF' })
  s += text(44, 418, '元数据库 · MySQL 8', { size: 12.5, weight: 600 })
  s += text(44, 440, '用户 / 实例 / 指标点 / 告警 /', { size: 11, fill: MUTED })
  s += text(44, 458, '备份记录 / 审计日志（Flyway 管理）', { size: 11, fill: MUTED })

  s += box(348, 392, 300, 96, { fill: '#FFFFFF' })
  s += text(368, 418, '冷层磁盘 · SSTable', { size: 12.5, weight: 600 })
  s += text(368, 440, 'BlockIndex · BloomFilter · CRC', { size: 11, fill: MUTED })
  s += text(368, 458, 'mmap 读 · 后台 Compaction', { size: 11, fill: MUTED })

  s += box(672, 392, 344, 96, { fill: '#FFFFFF' })
  s += text(692, 418, '可观测性出口', { size: 12.5, weight: 600 })
  s += text(692, 440, 'INFO / INFO sstables / 指标点 / 审计', { size: 11, fill: MUTED })
  s += text(692, 458, '控制台 5s 采集 · 三态展示（离线/未上报/零值）', { size: 11, fill: MUTED })

  s += arrow(268, 368, 268, 388, { stroke: '#9AA4B2' })
  s += arrow(776, 368, 500, 388, { stroke: '#9AA4B2' })
  s += arrow(880, 368, 880, 388, { stroke: '#9AA4B2' })

  s += text(24, 528, '关键约束：控制面只通过 RESP 访问内核，不共享进程、不直接读冷层文件；', { size: 11.5, fill: MUTED })
  s += text(24, 548, '内核的观测数据全部来自 INFO（含按需段落），控制面不做采样推算。', { size: 11.5, fill: MUTED })
  s += text(24, 584, '图 1　Tiering-KV 系统总体架构（数据平面 / 控制平面 / 元数据与冷层）', { size: 11, fill: FAINT })

  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">${box(0,0,W,H,{fill:'#FFFFFF',stroke:'#FFFFFF'})}${s}</svg>`
}

/* ---------------- 图 2：存储引擎数据流 ---------------- */
function fig2() {
  const W = 1040, H = 640
  let s = ''
  s += text(24, 30, '写入路径', { size: 11.5, fill: ACCENT, weight: 600 })
  const write = [['客户端写入', 'SET / HSET'], ['WAL', '段 64MB · fsync 策略'], ['MemTable', '分片跳表 · TTL · 热点']]
  write.forEach(([a, b], i) => {
    const x = 24 + i * 210
    s += box(x, 42, 190, 66, { fill: '#FFFFFF' })
    s += text(x + 14, 66, a, { size: 12.5, weight: 600 })
    s += text(x + 14, 88, b, { size: 10.5, fill: MUTED })
    if (i < 2) s += arrow(x + 192, 75, x + 208, 75)
  })
  s += text(24, 132, '水位线 70% / 85% / 95% → 触发 Flush / 迁移 / 背压', { size: 10.5, fill: WARN })

  s += text(24, 176, '冷层路径', { size: 11.5, fill: '#2F6B4F', weight: 600 })
  const cold = [['SSTable 文件', 'BlockIndex · Bloom · CRC'], ['Compaction', '阈值 8 张 · 输入→输出'], ['冷热迁移', '后台 worker · 失败计数']]
  cold.forEach(([a, b], i) => {
    const x = 24 + i * 210
    s += box(x, 188, 190, 66, { fill: COLD_SOFT, stroke: '#BFD8C9' })
    s += text(x + 14, 212, a, { size: 12.5, weight: 600 })
    s += text(x + 14, 234, b, { size: 10.5, fill: MUTED })
    if (i < 2) s += arrow(x + 192, 221, x + 208, 221, { stroke: '#7FA890' })
  })
  s += arrow(214, 110, 214, 184, { stroke: WARN, dash: '4 3' })

  s += text(24, 300, '读取路径', { size: 11.5, fill: ACCENT, weight: 600 })
  s += box(24, 312, 190, 66, { fill: '#FFFFFF' })
  s += text(38, 336, 'GET 请求', { size: 12.5, weight: 600 })
  s += text(38, 358, '先热层，后冷层', { size: 10.5, fill: MUTED })
  s += arrow(216, 345, 300, 345)
  s += box(304, 312, 190, 66, { fill: '#FFFFFF' })
  s += text(318, 336, 'BlockCache', { size: 12.5, weight: 600 })
  s += text(318, 358, '命中率 / 淘汰（INFO 上报）', { size: 10.5, fill: MUTED })
  s += arrow(496, 345, 580, 345)
  s += box(584, 312, 200, 66, { fill: '#FFFFFF' })
  s += text(598, 336, 'mmap 读 SSTable', { size: 12.5, weight: 600 })
  s += text(598, 358, '索引定位 → 块解码 → 校验', { size: 10.5, fill: MUTED })
  s += arrow(786, 345, 866, 345)
  s += box(870, 312, 146, 66, { fill: '#FFFFFF' })
  s += text(884, 336, '回源', { size: 12.5, weight: 600 })
  s += text(884, 358, 'copy-up 到热层', { size: 10.5, fill: MUTED })

  s += box(24, 410, 992, 120, { fill: '#FBFCFE', stroke: LINE })
  s += text(44, 436, '一次性成本边界（实测口径）', { size: 11.5, weight: 600, fill: MUTED })
  const notes = [
    'INFO 渲染：一次刷新 + 一次快照，O(表数) 复制，不做目录扫描',
    'INFO sstables：按需段落，不进入全量 INFO',
    '冷层精确计数（DBSIZE 口径）：冷层变更后首次全量迭代',
  ]
  notes.forEach((t, i) => s += text(48, 462 + i * 22, '· ' + t, { size: 11, fill: MUTED }))

  s += box(24, 552, 300, 36, { fill: ACCENT_SOFT, stroke: '#C7D2F5', rx: 8 })
  s += text(174, 575, '热数据：内存命中', { size: 11, fill: ACCENT, anchor: 'middle' })
  s += box(340, 552, 340, 36, { fill: COLD_SOFT, stroke: '#BFD8C9', rx: 8 })
  s += text(510, 575, '冷数据：SSTable + 页缓存 + BlockCache', { size: 11, fill: '#2F6B4F', anchor: 'middle' })
  s += box(696, 552, 320, 36, { fill: WARN_SOFT, stroke: '#EBD3AE', rx: 8 })
  s += text(856, 575, '内存上限由水位线约束，触发迁移/淘汰', { size: 11, fill: WARN, anchor: 'middle' })

  s += text(24, 620, '图 2　存储引擎数据流：写入（WAL→MemTable→Flush→Compaction）与读取（热层→BlockCache→SSTable）', { size: 11, fill: FAINT })
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">${box(0,0,W,H,{fill:'#FFFFFF',stroke:'#FFFFFF'})}${s}</svg>`
}

/* ---------------- 图 3：故障处理与恢复 ---------------- */
function fig3() {
  const W = 1040, H = 660
  let s = ''
  const lanes = [
    { y: 40, title: 'A. 实例离线', color: DANGER, fill: DANGER_SOFT, steps: ['探测失败', '状态置 DOWN', '接口 502/50201', '前端三态：— + 红条'], note: '控制面对 DOWN 实例不返回 live；离线错误码 50201，信息不含 SQL/堆栈' },
    { y: 190, title: 'B. 内核崩溃', color: WARN, fill: WARN_SOFT, steps: ['进程被终止', '重启读 WAL', '重放到最后落盘点', 'DBSIZE/键值校验'], note: '后台每 500ms flush+fsync；实测崩溃恢复 100/100 键存活，进程级计数归零' },
    { y: 340, title: 'C. 备份恢复', color: COLD, fill: COLD_SOFT, steps: ['校验 SUCCESS', '逐行回放', 'DEL + 写入（合并）', '审计 RESTORE_BACKUP'], note: '合并式、幂等、非原子；失败记 result=failed 并允许部分恢复（ADR-0365）' },
    { y: 490, title: 'D. 数据库断连', color: ACCENT, fill: ACCENT_SOFT, steps: ['连接超时 5s', '503 + 50300', '连接池保留', '恢复后自愈'], note: '仅三类连接异常映射 503；恢复 MySQL 后不重启控制面即自愈（实测 108ms）' },
  ]
  lanes.forEach((lane) => {
    s += box(24, lane.y, 992, 130, { fill: lane.fill, stroke: LINE, rx: 10 })
    s += text(44, lane.y + 28, lane.title, { size: 12.5, weight: 600, fill: lane.color })
    lane.steps.forEach((step, i) => {
      const x = 44 + i * 240
      s += box(x, lane.y + 46, 208, 62, { fill: '#FFFFFF', stroke: LINE, rx: 8 })
      s += text(x + 14, lane.y + 70, String(i + 1).padStart(2, '0'), { size: 10, fill: lane.color, mono: true })
      s += text(x + 34, lane.y + 70, step, { size: 12, weight: 600 })
      if (i < lane.steps.length - 1) s += arrow(x + 210, lane.y + 77, x + 236, lane.y + 77, { stroke: '#C3CBD5' })
    })
    s += text(44, lane.y + 122, lane.note ?? '', { size: 10.5, fill: MUTED })
  })
  lanes.forEach((lane) => {
    s += text(44, lane.y + 122, lane.note, { size: 10.5, fill: MUTED })
  })
  s += text(24, 640, '图 3　故障处理与恢复流程（四类故障的检测、降级与恢复路径，均以实测结果标注）', { size: 11, fill: FAINT })
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">${box(0,0,W,H,{fill:'#FFFFFF',stroke:'#FFFFFF'})}${s}</svg>`
}

fs.writeFileSync(path.join(OUT, 'fig1-architecture.svg'), fig1())
fs.writeFileSync(path.join(OUT, 'fig2-storage-flow.svg'), fig2())
fs.writeFileSync(path.join(OUT, 'fig3-failure-recovery.svg'), fig3())
console.log('svg written')