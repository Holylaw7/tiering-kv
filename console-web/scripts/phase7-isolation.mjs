#!/usr/bin/env node
/**
 * Phase 7 多实例隔离验收：
 * 1) 分别直连两个内核读 INFO；
 * 2) 通过控制面 API 读同一实例的实时指标；
 * 3) 断言控制面数值与对应内核一致，且两个实例互不相同。
 */
import net from 'node:net'

const CONSOLE = 'http://127.0.0.1:8080/api/v1'
const instances = [
  { id: 1, port: 6379, name: 'local-6379' },
  { id: 2, port: 6380, name: 'demo-tiering' }
]

function encode(parts) {
  let out = '*' + parts.length + '\r\n'
  for (const p of parts) {
    const s = String(p)
    out += '$' + Buffer.byteLength(s) + '\r\n' + s + '\r\n'
  }
  return out
}

function infoOf(port) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(port, '127.0.0.1')
    let buffer = ''
    socket.on('connect', () => socket.write(encode(['INFO'])))
    socket.on('data', (chunk) => {
      buffer += chunk.toString('utf8')
      const eol = buffer.indexOf('\r\n')
      if (eol < 0) return
      if (buffer[0] === '$') {
        const len = Number(buffer.slice(1, eol))
        if (buffer.length >= eol + 2 + len + 2) {
          socket.destroy()
          resolve(buffer.slice(eol + 2, eol + 2 + len))
        }
      }
    })
    socket.on('error', reject)
    setTimeout(() => { socket.destroy(); resolve(buffer) }, 3000)
  })
}

function pick(text, key) {
  const m = new RegExp('^' + key + ':(.*)$', 'm').exec(text)
  return m ? m[1].trim() : null
}

const login = await fetch(CONSOLE + '/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'admin', password: 'admin123' })
}).then((r) => r.json())
const token = login.data.token

const results = []
for (const target of instances) {
  const info = await infoOf(target.port)
  const api = await fetch(CONSOLE + '/dashboard/overview?instanceId=' + target.id, {
    headers: { Authorization: 'Bearer ' + token }
  }).then((r) => r.json())
  const live = api.data.live ?? {}
  const checks = [
    ['tieringReported', live.tieringReported === (pick(info, 'tiering_memtable_max_bytes') !== null)],
    ['memtableEntries', Number(live.memtableEntries) === Number(pick(info, 'tiering_memtable_entries'))],
    ['coldSstableCount', Number(live.coldSstableCount) === Number(pick(info, 'tiering_cold_sstable_count'))],
    ['coldDiskUsageBytes', Number(live.coldDiskUsageBytes) === Number(pick(info, 'tiering_cold_disk_usage_bytes'))],
    ['flushCount', Number(live.flushCount) === Number(pick(info, 'tiering_flush_count'))],
    ['compactionCount', Number(live.compactionCount) === Number(pick(info, 'tiering_compaction_count'))],
    ['cacheHits', Number(live.cacheHits) === Number(pick(info, 'tiering_block_cache_hits'))],
    ['version', String(live.version) === String(pick(info, 'redis_version'))],
    ['port', String(api.data.selected.port) === String(target.port)]
  ]
  const failed = checks.filter(([, ok]) => !ok)
  results.push({
    instance: target.name,
    port: target.port,
    checks: checks.length,
    failed: failed.length,
    detail: failed.map(([name]) => name),
    snapshot: {
      entries: live.memtableEntries,
      sstables: live.coldSstableCount,
      coldBytes: live.coldDiskUsageBytes,
      flush: live.flushCount,
      compaction: live.compactionCount,
      cacheHits: live.cacheHits
    }
  })
}

const [a, b] = results
const identical = JSON.stringify(a.snapshot) === JSON.stringify(b.snapshot)
console.log(JSON.stringify({ results, instancesDistinct: !identical }, null, 2))
const ok = results.every((r) => r.failed === 0) && !identical
process.exit(ok ? 0 : 1)
