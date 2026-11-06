/**
 * E2E-5 备份：创建（异步）→ 轮询成功 → 下载 → 改数据 → 恢复 → 校验真实数据 → 并发创建 200/409。
 *
 * 并发窗口：先写入足量键，让导出持续足够时间；两次请求用 Promise.all 同时发出。
 */
import { api, check, exitWith, login, respClient, waitFor } from './lib.mjs'

const admin = await login('admin', 'admin123')
const token = admin.token

const instances = await api('/instances', { token })
const target = instances.json.data.find((item) => item.name === 'phase10-kernel')
const instanceId = target.id

const client = respClient(target.port)
await client.ready

// 1) 准备可校验的数据（含逗号与引号，验证备份/恢复对特殊字符的处理）
const k1 = 'phase10:bk:a_' + Date.now()
const k2 = 'phase10:bk:b_' + Date.now()
const v1 = 'value-with,comma'
const v2 = 'value-with"quote'
await client.call('SET', k1, v1)
await client.call('SET', k2, v2)

// 扩容：让导出有足够时长，构造并发重叠
for (let i = 0; i < 3000; i++) {
  await client.call('SET', 'phase10:bulk:' + i, 'x'.repeat(64))
}

// 2) 创建备份并等待完成
const created = await api(`/backups?instanceId=${instanceId}`, { method: 'POST', token })
check('创建备份返回记录', created.status === 200 && Boolean(created.json?.data?.id),
  'status=' + created.status)
const backupId = created.json.data.id

const finished = await waitFor(async () => {
  const list = await api(`/backups?instanceId=${instanceId}`, { token })
  const row = list.json.data.find((item) => item.id === backupId)
  return row && row.status !== 'RUNNING' ? row : null
}, { timeoutMs: 60000, intervalMs: 500, label: '备份完成' })
check('备份任务成功', finished.status === 'SUCCESS', 'status=' + finished.status)
check('备份记录键数 > 0', finished.keyCount > 0, 'keys=' + finished.keyCount)
check('备份文件大小 > 0', finished.sizeBytes > 0, 'bytes=' + finished.sizeBytes)

// 3) 下载
const download = await api(`/backups/${backupId}/download`, { token, raw: true })
check('下载返回 200', download.status === 200, 'status=' + download.status)
check('下载带有附件头', String(download.headers.get('content-disposition') || '').includes('attachment'),
  download.headers.get('content-disposition') || '')
check('下载内容非空', download.buffer.length > 0, 'bytes=' + download.buffer.length)

// 4) 破坏数据后恢复
await client.call('SET', k1, 'CORRUPTED')
await client.call('DEL', k2)
const afterCorruption = await client.call('GET', k1)
check('数据已被破坏（前置）', afterCorruption === 'CORRUPTED')

const restored = await api(`/backups/${backupId}/restore`, { method: 'POST', token })
check('恢复接口 200', restored.status === 200, 'status=' + restored.status)
check('恢复返回键数 > 0', Number(restored.json?.data) > 0, 'keys=' + restored.json?.data)

const restoredV1 = await client.call('GET', k1)
const restoredV2 = await client.call('GET', k2)
check('恢复后键值 1 与备份时一致', restoredV1 === v1, 'value=' + JSON.stringify(restoredV1))
check('恢复后键值 2 与备份时一致', restoredV2 === v2, 'value=' + JSON.stringify(restoredV2))

// 5) 并发创建：同时发两个请求，期望一个 200、一个 409
const [a, b] = await Promise.all([
  api(`/backups?instanceId=${instanceId}`, { method: 'POST', token }),
  api(`/backups?instanceId=${instanceId}`, { method: 'POST', token })
])
const statuses = [a.status, b.status].sort()
check('并发创建产生 200 + 409', statuses[0] === 200 && statuses[1] === 409,
  'statuses=' + statuses.join(','))

// 等两个任务都结束（避免影响后续用例）
await waitFor(async () => {
  const list = await api(`/backups?instanceId=${instanceId}`, { token })
  return list.json.data.every((item) => item.status !== 'RUNNING')
}, { timeoutMs: 90000, intervalMs: 800, label: '并发备份结束' })

client.close()
exitWith('E2E-5 备份')
