#!/usr/bin/env node
/** 打印某端口内核的分层指标快照（JSON），供 Phase 7 生命周期脚本断言。 */
import net from 'node:net'

const PORT = Number(process.argv[2] || 6381)

function encode(parts) {
  let out = '*' + parts.length + '\r\n'
  for (const p of parts) {
    const s = String(p)
    out += '$' + Buffer.byteLength(s) + '\r\n' + s + '\r\n'
  }
  return out
}

const info = await new Promise((resolve, reject) => {
  const socket = net.connect(PORT, '127.0.0.1')
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

function num(key) {
  const m = new RegExp('^' + key + ':(.*)$', 'm').exec(info)
  return m ? Number(m[1].trim()) : null
}

console.log(JSON.stringify({
  hasTieringSection: /# Tiering\r\n/.test(info),
  memtableBytes: num('tiering_memtable_used_bytes'),
  memtableEntries: num('tiering_memtable_entries'),
  sstables: num('tiering_cold_sstable_count'),
  coldBytes: num('tiering_cold_disk_usage_bytes'),
  flushCount: num('tiering_flush_count'),
  compactionCount: num('tiering_compaction_count'),
  cacheHits: num('tiering_block_cache_hits'),
  cacheMisses: num('tiering_block_cache_misses'),
  flushInProgress: num('tiering_flush_in_progress'),
  compactionInProgress: num('tiering_compaction_in_progress'),
  totalKeys: num('total_keys')
}))
