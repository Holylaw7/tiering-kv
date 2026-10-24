#!/usr/bin/env node
// 控制面接口级端到端冒烟：认证 → 权限 → 终端 → 键空间 → 告警 → 备份/恢复 → 审计
// 前置：tiering-kv 在 6379、console-server 在 8080
// 用法：node scripts/api-smoke.mjs [--base http://127.0.0.1:8080]
const args = process.argv.slice(2)
const index = args.indexOf('--base')
const BASE = index >= 0 && args[index + 1] ? args[index + 1] : 'http://127.0.0.1:8080'

let token = ''
const results = []
let failed = 0

async function call(method, path, body, options = {}) {
  const headers = { 'Content-Type': 'application/json' }
  const useToken = options.token ?? token
  if (useToken) headers.Authorization = 'Bearer ' + useToken
  const response = await fetch(BASE + '/api/v1' + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  const text = await response.text()
  let json
  try {
    json = JSON.parse(text)
  } catch {
    json = { raw: text }
  }
  return { status: response.status, json, text }
}

function check(name, condition, detail = '') {
  if (condition) {
    results.push('  PASS  ' + name)
  } else {
    failed++
    results.push('  FAIL  ' + name + (detail ? ' -> ' + detail : ''))
  }
}

async function login(username, password) {
  const res = await call('POST', '/auth/login', { username, password }, { token: '' })
  return res
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

// ---------- 1. 认证 ----------
let res = await login('admin', 'admin123')
check('管理员登录', res.status === 200 && res.json.code === 0, res.text.slice(0, 120))
token = res.json?.data?.token ?? ''

res = await login('admin', 'wrong-password')
check('错误口令返回 401', res.status === 401)

res = await call('GET', '/instances', undefined, { token: '' })
check('匿名访问被拒绝', res.status === 401)

// ---------- 2. 实例与权限 ----------
res = await call('GET', '/instances')
const instance = res.json?.data?.[0]
check('实例列表非空', !!instance, res.text.slice(0, 120))
const instanceId = instance?.id

res = await call('POST', '/instances', { name: 'dup-check', host: instance.host, port: instance.port })
check('重复地址纳管返回 409', res.status === 409, res.text.slice(0, 120))

res = await call('POST', '/instances/' + instanceId + '/probe')
check('实例探测可用', res.status === 200 && res.json?.data?.status === 'UP', res.text.slice(0, 160))

// ---------- 3. 终端 ----------
res = await call('POST', '/terminal/execute', { instanceId, command: 'SET smoke:key "hello smoke"' })
check('终端写入命令', res.status === 200 && res.json?.data?.payload === 'OK', res.text.slice(0, 160))

res = await call('POST', '/terminal/execute', { instanceId, command: 'GET smoke:key' })
check('终端读取命令', res.json?.data?.payload === 'hello smoke', res.text.slice(0, 160))

res = await call('POST', '/terminal/execute', { instanceId, command: 'FLUSHALL' })
check('危险命令被拦截', res.status === 400, res.text.slice(0, 160))

// ---------- 4. 键空间 ----------
res = await call('GET', '/keys/detail?instanceId=' + instanceId + '&key=smoke:key')
check('键详情返回类型与值', res.json?.data?.type === 'string' && res.json?.data?.value === 'hello smoke',
  res.text.slice(0, 160))

res = await call('PUT', '/keys/ttl?instanceId=' + instanceId, { key: 'smoke:key', ttlSeconds: 600 })
check('设置 TTL', res.status === 200, res.text.slice(0, 120))

res = await call('GET', '/keys/detail?instanceId=' + instanceId + '&key=smoke:key')
check('TTL 生效', res.json?.data?.ttl > 500 && res.json?.data?.ttl <= 600, String(res.json?.data?.ttl))

// ---------- 5. 告警 ----------
res = await call('POST', '/alerts/rules', {
  name: 'smoke-rule-' + Date.now(), instanceId, metric: 'total_keys',
  operator: 'GT', threshold: 0, durationSeconds: 0, level: 'WARN', enabled: true
})
const ruleId = res.json?.data?.id
check('创建告警规则', res.status === 200 && !!ruleId, res.text.slice(0, 160))

await sleep(7000)
res = await call('GET', '/alerts/events?status=FIRING&page=1&size=20')
const firing = (res.json?.data?.items ?? []).find((item) => item.ruleId === ruleId)
check('阈值满足后触发告警', !!firing, res.text.slice(0, 160))

if (firing) {
  res = await call('POST', '/alerts/events/' + firing.id + '/resolve')
  check('告警可人工恢复', res.status === 200, res.text.slice(0, 120))
}
await call('DELETE', '/alerts/rules/' + ruleId)

// ---------- 6. 备份与恢复 ----------
const backupKeys = 3
for (let i = 0; i < backupKeys; i++) {
  await call('POST', '/terminal/execute', { instanceId, command: 'SET smoke:bk:' + i + ' v' + i })
}
res = await call('POST', '/backups?instanceId=' + instanceId)
const backup = res.json?.data
check('触发逻辑备份', res.status === 200 && backup?.status === 'SUCCESS' && backup?.keyCount > 0,
  res.text.slice(0, 160))

if (backup) {
  const download = await fetch(BASE + '/api/v1/backups/' + backup.id + '/download', {
    headers: { Authorization: 'Bearer ' + token }
  })
  const size = (await download.arrayBuffer()).byteLength
  check('备份可下载且非空', download.status === 200 && size > 0, 'size=' + size)

  await call('DELETE', '/keys?instanceId=' + instanceId + '&key=smoke:key')
  await call('DELETE', '/keys?instanceId=' + instanceId + '&key=smoke:bk:0')
  res = await call('POST', '/backups/' + backup.id + '/restore')
  check('恢复返回键数', res.status === 200 && res.json?.data > 0, res.text.slice(0, 160))

  res = await call('GET', '/keys/detail?instanceId=' + instanceId + '&key=smoke:bk:0')
  check('恢复后键可读', res.json?.data?.value === 'v0', res.text.slice(0, 160))

  await call('DELETE', '/backups/' + backup.id)
}

// ---------- 7. 审计 ----------
res = await call('GET', '/audit?page=1&size=20')
const actions = (res.json?.data?.items ?? []).map((item) => item.action)
check('审计记录包含键删除', actions.includes('KEY_DELETE'), actions.join(','))
check('审计记录包含登录', actions.includes('LOGIN'), actions.join(','))

// ---------- 8. 只读角色 ----------
const viewerName = 'smoke_viewer_' + Date.now()
res = await call('POST', '/users', { username: viewerName, password: 'viewer123', role: 'VIEWER' })
const viewerId = res.json?.data?.id
check('创建只读用户', res.status === 200 && !!viewerId, res.text.slice(0, 160))

res = await login(viewerName, 'viewer123')
const viewerToken = res.json?.data?.token
check('只读用户可登录', !!viewerToken)

res = await call('DELETE', '/keys?instanceId=' + instanceId + '&key=smoke:bk:1', undefined, { token: viewerToken })
check('只读用户删除键被拒绝', res.status === 403, res.text.slice(0, 120))

res = await call('GET', '/users', undefined, { token: viewerToken })
check('只读用户访问用户管理被拒绝', res.status === 403, res.text.slice(0, 120))

if (viewerId) {
  await call('DELETE', '/users/' + viewerId)
}

// ---------- 汇总 ----------
console.log(results.join('\n'))
console.log(failed === 0 ? '\n接口冒烟全部通过' : '\n失败 ' + failed + ' 项')
process.exit(failed === 0 ? 0 : 1)
