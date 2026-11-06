/**
 * Phase 10 E2E 公共库：登录、API 调用、断言、轮询、RESP 直连、CSV 解析、清理。
 * 约定：每个脚本结束时输出 PASS/FAIL 汇总；任一断言失败 → 退出码 1。
 */
import net from 'node:net'

export const CONSOLE = process.env.CONSOLE_BASE || 'http://127.0.0.1:8080/api/v1'
export const TEST_PORT = Number(process.env.TEST_KERNEL_PORT || 6383)
export const TEST_INSTANCE = process.env.TEST_INSTANCE_NAME || 'phase10-kernel'

const results = []

export function check(name, ok, detail = '') {
  results.push({ name, ok: Boolean(ok), detail })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' — ' + detail : ''}`)
  return Boolean(ok)
}

export function summary(label) {
  const failed = results.filter((r) => !r.ok)
  console.log(`\n[${label}] 总结：${results.length} 项，通过 ${results.length - failed.length}，失败 ${failed.length}`)
  return failed.length === 0
}

export function exitWith(label) {
  const ok = summary(label)
  setTimeout(() => process.exit(ok ? 0 : 1), 100)
}

export async function api(path, { method = 'GET', token, body, headers = {}, raw = false } = {}) {
  const init = {
    method,
    headers: {
      Origin: 'http://127.0.0.1:5173',
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
      ...headers
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  }
  const res = await fetch(CONSOLE + path, init)
  if (raw) {
    return { status: res.status, headers: res.headers, buffer: Buffer.from(await res.arrayBuffer()) }
  }
  const text = await res.text()
  let json = null
  try { json = JSON.parse(text) } catch { /* 非 JSON */ }
  return { status: res.status, json, text }
}

export async function login(username = 'admin', password = 'admin123') {
  const res = await api('/auth/login', { method: 'POST', body: { username, password } })
  if (res.status !== 200 || !res.json?.data?.token) {
    throw new Error('登录失败：' + res.status + ' ' + res.text)
  }
  return { token: res.json.data.token, user: res.json.data.user ?? res.json.data }
}

/** 反复调用 fn 直到返回真值或超时；返回最后一次结果。 */
export async function waitFor(fn, { timeoutMs = 15000, intervalMs = 300, label = '条件' } = {}) {
  const deadline = Date.now() + timeoutMs
  let last
  while (Date.now() < deadline) {
    last = await fn()
    if (last) return last
    await new Promise((r) => setTimeout(r, intervalMs))
  }
  throw new Error(`等待${label}超时（${timeoutMs}ms）`)
}

/* ---------------- RESP 直连（验证内核真实状态） ---------------- */

function encode(parts) {
  let out = '*' + parts.length + '\r\n'
  for (const p of parts) {
    const s = String(p)
    out += '$' + Buffer.byteLength(s) + '\r\n' + s + '\r\n'
  }
  return out
}

export function respClient(port = TEST_PORT) {
  const socket = net.connect(port, '127.0.0.1')
  socket.setNoDelay(true)
  let buffer = Buffer.alloc(0)
  const waiters = []
  socket.on('data', (chunk) => {
    buffer = Buffer.concat([buffer, chunk])
    for (;;) {
      const parsed = parseReply(buffer)
      if (!parsed) break
      buffer = buffer.subarray(parsed.consumed)
      const waiter = waiters.shift()
      if (waiter) waiter(parsed.value)
    }
  })
  return {
    ready: new Promise((resolve, reject) => {
      socket.once('connect', resolve)
      socket.once('error', reject)
    }),
    call(...args) {
      const done = new Promise((resolve) => waiters.push(resolve))
      socket.write(encode(args))
      return done
    },
    close() { socket.destroy() }
  }
}

function parseReply(buf) {
  if (!buf.length) return null
  const type = String.fromCharCode(buf[0])
  const eol = buf.indexOf('\r\n')
  if (eol < 0) return null
  const line = buf.subarray(1, eol).toString('utf8')
  if (type === '+') return { value: line, consumed: eol + 2 }
  if (type === '-') return { value: { error: line }, consumed: eol + 2 }
  if (type === ':') return { value: Number(line), consumed: eol + 2 }
  if (type === '$') {
    const len = Number(line)
    if (len < 0) return { value: null, consumed: eol + 2 }
    if (buf.length < eol + 2 + len + 2) return null
    return { value: buf.subarray(eol + 2, eol + 2 + len).toString('utf8'), consumed: eol + 2 + len + 2 }
  }
  if (type === '*') {
    const count = Number(line)
    let offset = eol + 2
    const items = []
    for (let i = 0; i < count; i++) {
      const item = parseReply(buf.subarray(offset))
      if (!item) return null
      items.push(item.value)
      offset += item.consumed
    }
    return { value: items, consumed: offset }
  }
  return { value: null, consumed: eol + 2 }
}

/* ---------------- CSV（RFC4180 子集：支持引号转义与逗号） ---------------- */

export function parseCsv(text) {
  const rows = []
  let row = []
  let field = ''
  let quoted = false
  const src = text.replace(/^\uFEFF/, '')
  for (let i = 0; i < src.length; i++) {
    const ch = src[i]
    if (quoted) {
      if (ch === '"') {
        if (src[i + 1] === '"') { field += '"'; i++ } else { quoted = false }
      } else {
        field += ch
      }
      continue
    }
    if (ch === '"') { quoted = true; continue }
    if (ch === ',') { row.push(field); field = ''; continue }
    if (ch === '\r') continue
    if (ch === '\n') { row.push(field); rows.push(row); row = []; field = ''; continue }
    field += ch
  }
  if (field.length > 0 || row.length > 0) { row.push(field); rows.push(row) }
  return rows
}
