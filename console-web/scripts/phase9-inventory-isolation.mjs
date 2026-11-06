#!/usr/bin/env node
/**
 * Phase 9：SSTable 清单的多实例隔离验收。
 * 对每个实例，比对"控制面返回的清单"与"直连内核 INFO sstables"。
 */
import net from 'node:net'

const CONSOLE = 'http://127.0.0.1:8080/api/v1'
const targets = [
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

function kernelTables(port) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(port, '127.0.0.1')
    let buffer = ''
    socket.on('connect', () => socket.write(encode(['INFO', 'sstables'])))
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

const login = await fetch(CONSOLE + '/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'admin', password: 'admin123' })
}).then((r) => r.json())
const token = login.data.token

const results = []
for (const target of targets) {
  const text = await kernelTables(target.port)
  const api = await fetch(CONSOLE + '/system/tables?instanceId=' + target.id, {
    headers: { Authorization: 'Bearer ' + token }
  }).then((r) => r.json())

  const kernelRows = text.split('\r\n').filter((line) => line.startsWith('sstable:') && line.includes('|keys='))
  const view = api.data
  const kernelTotal = Number(/sstable_total_bytes:(\d+)/.exec(text)?.[1] ?? -1)
  const kernelCount = Number(/sstable_count:(\d+)/.exec(text)?.[1] ?? -1)
  const kernelOrder = /sstable_key_order:(\w+)/.exec(text)?.[1] ?? 'unknown'

  const checks = {
    count: view.count === kernelCount && view.tables.length === kernelRows.length,
    totalBytes: view.totalBytes === kernelTotal,
    keyOrder: view.keyOrder === kernelOrder,
    idsAscending: view.tables.every((t, i) => i === 0 || view.tables[i - 1].id < t.id),
    rawKeysPresent: view.tables.every((t) => typeof t.firstKeyBase64 === 'string' && typeof t.lastKeyBase64 === 'string')
  }
  results.push({
    instance: target.name,
    port: target.port,
    tables: kernelCount,
    checks,
    failed: Object.entries(checks).filter(([, ok]) => !ok).map(([k]) => k)
  })
}

const [a, b] = results
const distinct = a.tables !== b.tables || JSON.stringify(a.checks) !== JSON.stringify(b.checks)
console.log(JSON.stringify({ results, distinct }, null, 2))
const ok = results.every((r) => r.failed.length === 0) && distinct
// 等待 socket 全部销毁后再退出，避免 libuv 在退出阶段的断言
setTimeout(() => process.exit(ok ? 0 : 1), 150)