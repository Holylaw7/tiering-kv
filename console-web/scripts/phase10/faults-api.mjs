/**
 * Phase 10 故障注入（API 侧断言）。
 * 用法：node faults-api.mjs <stage>
 *   stage=f1   实例离线：overview 无 live + DOWN，清单接口 502
 *   stage=pre  写入 100 个唯一键（供 F2 崩溃恢复校验）
 *   stage=post 重启后校验：逐键读回、DBSIZE、计数器归零
 *   stage=f4   Token：篡改签名 401；签名有效但已过期 401
 */
import crypto from 'node:crypto'
import { api, check, exitWith, login, respClient, waitFor, CONSOLE } from './lib.mjs'

const stage = process.argv[2]

const JWT_SECRET = process.env.CONSOLE_JWT_SECRET
  || 'tiering-kv-console-dev-secret-key-change-me-please-0123456789'

function b64url(input) {
  return Buffer.from(input).toString('base64').replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_')
}

function mintToken(claims) {
  const header = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))
  const payload = b64url(JSON.stringify(claims))
  const data = header + '.' + payload
  const signature = crypto.createHmac('sha256', JWT_SECRET).update(data).digest('base64')
    .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_')
  return data + '.' + signature
}

if (stage === 'f1') {
  const admin = await login('admin', 'admin123')
  const token = admin.token
  const instances = await api('/instances', { token })
  let down = instances.json.data.find((item) => item.port === 6399)
  if (!down) {
    const created = await api('/instances', {
      method: 'POST', token,
      body: { name: 'phase10-offline-' + Date.now(), host: '127.0.0.1', port: 6399, remark: 'F1 离线用例', enabled: true }
    })
    down = created.json.data
  }
  // 新纳管实例初始为 UNKNOWN，显式探测一次把它落到 DOWN
  await api(`/instances/${down.id}/probe`, { method: 'POST', token })

  const overview = await api('/dashboard/overview?instanceId=' + down.id, { token })
  check('离线实例 overview 返回 200', overview.status === 200, 'status=' + overview.status)
  const selected = overview.json?.data?.selected
  check('离线实例状态为 DOWN', selected?.status === 'DOWN', 'status=' + selected?.status)
  check('离线实例无实时指标（live 为空）', !overview.json?.data?.live)

  const tables = await api('/system/tables?instanceId=' + down.id, { token })
  check('离线实例清单接口 502', tables.status === 502, 'status=' + tables.status)
  check('离线错误信息明确（不含 SQL/堆栈）',
    !/SQLException|java\.|at io\.tieringkv/.test(tables.text), tables.text.slice(0, 80))

  const info = await api('/system/info?instanceId=' + down.id, { token })
  check('离线实例 INFO 接口 5xx', info.status >= 500, 'status=' + info.status)

  await api('/instances/' + down.id, { method: 'DELETE', token })
  exitWith('F1 实例离线')
}

if (stage === 'pre') {
  const client = respClient(6383)
  await client.ready
  for (let i = 0; i < 100; i++) {
    await client.call('SET', 'phase10:wal:' + i, 'v' + i)
  }
  const count = await client.call('DBSIZE')
  // wal.fsync=EVERY_SEC：等待超过一个 fsync 周期，落盘后再制造崩溃，
  // 这样校验的是"配置承诺的持久化边界"，而不是"刚写入未落盘"的必然丢失。
  const fsyncWaitMs = Number(process.env.WAL_FSYNC_WAIT_MS || 1800)
  await new Promise((r) => setTimeout(r, fsyncWaitMs))
  const countAfterWait = await client.call('DBSIZE')
  client.close()
  console.log(JSON.stringify({ written: 100, dbsizeBeforeWait: count, dbsizeAfterWait: countAfterWait, fsyncWaitMs }))
  process.exit(0)
}

if (stage === 'post') {
  const client = respClient(6383)
  await client.ready
  let missing = 0
  let wrong = 0
  for (let i = 0; i < 100; i++) {
    const value = await client.call('GET', 'phase10:wal:' + i)
    if (value === null) missing++
    else if (value !== 'v' + i) wrong++
  }
  const dbsize = await client.call('DBSIZE')
  client.close()

  check('崩溃重启后 100 个键全部存在', missing === 0, 'missing=' + missing)
  check('崩溃重启后键值无损坏', wrong === 0, 'wrong=' + wrong)
  check('DBSIZE 不低于写入量', dbsize >= 100, 'DBSIZE=' + dbsize)

  const admin = await login('admin', 'admin123')
  const instances = await api('/instances', { token: admin.token })
  const target = instances.json.data.find((item) => item.port === 6383)
  // 内核刚重启时实例状态可能仍是 DOWN，控制面对 DOWN 实例不返回 live；
  // 这里等待采集器把它重新标记为 UP（最长 20s），再断言指标
  const counters = await waitFor(async () => {
    const live = await api('/dashboard/overview?instanceId=' + target.id, { token: admin.token })
    return live.json?.data?.live ?? null
  }, { timeoutMs: 20000, intervalMs: 1000, label: '实例重新上线并返回实时指标' })
  check('进程级计数器重启后归零（flush/compaction）',
    (counters?.flushCount ?? 0) === 0 && (counters?.compactionCount ?? 0) === 0,
    'flush=' + counters?.flushCount + ' compaction=' + counters?.compactionCount)
  check('重启后仍上报分层指标', counters?.tieringReported === true)

  exitWith('F2 崩溃恢复')
}

if (stage === 'f4') {
  const admin = await login('admin', 'admin123')
  const valid = admin.token

  const parts = valid.split('.')
  const tampered = parts[0] + '.' + parts[1] + '.' + parts[2].slice(0, -2) + 'AA'
  const tamperedRes = await api('/auth/me', { token: tampered })
  check('篡改签名的 Token 被拒 401', tamperedRes.status === 401, 'status=' + tamperedRes.status)

  const now = Math.floor(Date.now() / 1000)
  const expired = mintToken({
    sub: 'admin', uid: 1, role: 'ADMIN', iat: now - 7200, exp: now - 3600
  })
  const expiredRes = await api('/auth/me', { token: expired })
  check('签名有效但已过期的 Token 被拒 401', expiredRes.status === 401, 'status=' + expiredRes.status)

  const future = mintToken({ sub: 'admin', uid: 1, role: 'ADMIN', iat: now, exp: now + 600 })
  const futureRes = await api('/auth/me', { token: future })
  check('签名有效且未过期的自签 Token 可用（证明 401 来自过期而非签名）',
    futureRes.status === 200, 'status=' + futureRes.status)

  exitWith('F4 Token 校验')
}