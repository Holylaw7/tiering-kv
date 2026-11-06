/**
 * E2E-3 键空间：写入 → 详情 → hash 字段增删 → TTL 真实过期（带超时轮询）→ 删除后确认为空。
 */
import { api, check, exitWith, login, respClient, waitFor } from './lib.mjs'

const admin = await login('admin', 'admin123')
const token = admin.token

// 找到 phase10 测试实例（E2E-2 会临时创建实例，这里用固定名称的实例）
const instances = await api('/instances', { token })
const target = instances.json.data.find((item) => item.name === 'phase10-kernel')
  ?? instances.json.data.find((item) => item.remark?.includes('冷热分层演示'))
if (!target) {
  console.log('FAIL 未找到测试实例（需要用 phase10-kernel:6383）')
  setTimeout(() => process.exit(1), 100)
}
const instanceId = target.id
check('找到测试实例', Boolean(instanceId), 'id=' + instanceId + ' port=' + target.port)

const key = 'phase10:string:' + Date.now()
const value = 'hello-phase10'
const hashKey = 'phase10:hash:' + Date.now()
const ttlKey = 'phase10:ttl:' + Date.now()

// 1) string 写入 + 读回
const put = await api(`/keys?instanceId=${instanceId}`, { method: 'PUT', token, body: { key, value } })
check('写入 string 键 200', put.status === 200, 'status=' + put.status)

const detail = await api(`/keys/detail?instanceId=${instanceId}&key=${encodeURIComponent(key)}`, { token })
check('详情值与写入一致', detail.json?.data?.value === value, 'value=' + detail.json?.data?.value)
check('详情类型为 string', detail.json?.data?.type === 'string', 'type=' + detail.json?.data?.type)

// 2) hash 字段
await api(`/keys/hash-field?instanceId=${instanceId}`, {
  method: 'PUT', token, body: { key: hashKey, field: 'f1', value: 'v1' }
})
await api(`/keys/hash-field?instanceId=${instanceId}`, {
  method: 'PUT', token, body: { key: hashKey, field: 'f2', value: 'v2' }
})
const hashDetail = await api(`/keys/detail?instanceId=${instanceId}&key=${encodeURIComponent(hashKey)}`, { token })
const hashValue = hashDetail.json?.data?.value ?? {}
check('hash 写入两个字段', JSON.stringify(hashValue).includes('f1') && JSON.stringify(hashValue).includes('f2'),
  'value=' + JSON.stringify(hashValue).slice(0, 80))

const delField = await api(
  `/keys/hash-field?instanceId=${instanceId}&key=${encodeURIComponent(hashKey)}&field=f2`,
  { method: 'DELETE', token }
)
check('删除 hash 字段 200', delField.status === 200, 'status=' + delField.status)
const hashAfter = await api(`/keys/detail?instanceId=${instanceId}&key=${encodeURIComponent(hashKey)}`, { token })
const afterValue = JSON.stringify(hashAfter.json?.data?.value ?? {})
check('字段 f2 已移除', !afterValue.includes('"f2"'), 'value=' + afterValue.slice(0, 80))

// 3) TTL 真实过期
const ttlSeconds = 2
// 先确保键存在再设置 TTL（接口对不存在键的行为单独验证）
await api(`/keys?instanceId=${instanceId}`, { method: 'PUT', token, body: { key: ttlKey, value: 'temp' } })
await api(`/keys/ttl?instanceId=${instanceId}`, { method: 'PUT', token, body: { key: ttlKey, ttlSeconds } })

const ttlDetail = await api(`/keys/detail?instanceId=${instanceId}&key=${encodeURIComponent(ttlKey)}`, { token })
const ttlRemaining = ttlDetail.json?.data?.ttl
check('TTL 设置后剩余时间在预期范围', ttlRemaining > 0 && ttlRemaining <= ttlSeconds,
  'ttl=' + ttlRemaining + 's')

// 等待真实过期：以内核 EXISTS 为准（控制面的 ttl 会向下取整到 0，不能作为到期判据）
const probe = respClient(target.port)
await probe.ready
const expired = await waitFor(async () => (await probe.call('EXISTS', ttlKey)) === 0,
  { timeoutMs: 10000, intervalMs: 400, label: 'TTL 到期（内核 EXISTS=0）' })
check('TTL 到期后内核中键消失', Boolean(expired))

const detailAfterExpiry = await api(`/keys/detail?instanceId=${instanceId}&key=${encodeURIComponent(ttlKey)}`, { token })
check('TTL 到期后控制面读不到值',
  detailAfterExpiry.json?.data?.value === null || detailAfterExpiry.json?.data?.value === undefined
  || detailAfterExpiry.status === 404,
  'status=' + detailAfterExpiry.status)

// 4) 删除
const del = await api(`/keys?instanceId=${instanceId}&key=${encodeURIComponent(key)}`, { method: 'DELETE', token })
check('删除键 200', del.status === 200, 'status=' + del.status)

// 以内核为准复核（绕过控制面）
const client = probe
const exists = await client.call('EXISTS', key)
const hashExists = await client.call('EXISTS', hashKey)
const ttlExists = await client.call('EXISTS', ttlKey)
client.close()
check('内核确认键已删除（EXISTS=0）', exists === 0, 'EXISTS=' + exists)
check('hash 键仍存在（未误删）', hashExists === 1, 'EXISTS=' + hashExists)
check('TTL 键已过期（EXISTS=0）', ttlExists === 0, 'EXISTS=' + ttlExists)

exitWith('E2E-3 键空间')