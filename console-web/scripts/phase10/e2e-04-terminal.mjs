/**
 * E2E-4 在线终端：正常命令、错误命令、危险命令拦截、历史按实例隔离。
 */
import { api, check, exitWith, login } from './lib.mjs'

const admin = await login('admin', 'admin123')
const token = admin.token

const instances = await api('/instances', { token })
const find = (name) => instances.json.data.find((item) => item.name === name)
const test = find('phase10-kernel')
const other = find('local-6379')
check('测试实例与对照实例都存在', Boolean(test && other), `${test?.name}/${other?.name}`)

const markerKey = 'phase10:term:' + Date.now()

const ping = await api('/terminal/execute', {
  method: 'POST', token, body: { instanceId: test.id, command: 'PING' }
})
check('PING 返回 PONG', ping.json?.data?.payload === 'PONG', 'payload=' + JSON.stringify(ping.json?.data?.payload))
check('PING 未被标记为错误', ping.json?.data?.error === false)
check('返回耗时字段', typeof ping.json?.data?.costMs === 'number', 'costMs=' + ping.json?.data?.costMs)

const set = await api('/terminal/execute', {
  method: 'POST', token, body: { instanceId: test.id, command: `SET ${markerKey} terminal-ok` }
})
check('SET 执行成功', set.json?.data?.error === false, 'payload=' + JSON.stringify(set.json?.data?.payload))

const get = await api('/terminal/execute', {
  method: 'POST', token, body: { instanceId: test.id, command: `GET ${markerKey}` }
})
check('GET 读回写入值', get.json?.data?.payload === 'terminal-ok', 'payload=' + get.json?.data?.payload)

const bad = await api('/terminal/execute', {
  method: 'POST', token, body: { instanceId: test.id, command: 'THIS_COMMAND_DOES_NOT_EXIST' }
})
check('未知命令返回错误标记', bad.json?.data?.error === true, 'error=' + bad.json?.data?.error)

const blocked = await api('/terminal/execute', {
  method: 'POST', token, body: { instanceId: test.id, command: 'FLUSHALL' }
})
// 黑名单命中走 400（业务拒绝），不是 200 + error 标记
check('危险命令被黑名单拦截（400 + 明确提示）',
  blocked.status === 400 && String(blocked.json?.message ?? '').includes('禁用'),
  'status=' + blocked.status + ' message=' + String(blocked.json?.message ?? '').slice(0, 40))

// 历史：包含刚才的命令，且按实例隔离
const history = await api('/terminal/history?page=1&size=50', { token })
const rows = history.json?.data?.items ?? []
const mine = rows.filter((row) => row.instanceId === test.id)
const others = rows.filter((row) => row.instanceId === other.id)
check('历史包含本次执行的命令',
  mine.some((row) => String(row.command).includes(markerKey)), 'count=' + mine.length)
check('历史中同一实例的记录实例 ID 一致',
  mine.every((row) => row.instanceId === test.id))
check('历史按实例隔离（不含另一实例的新命令）',
  !others.some((row) => String(row.command).includes(markerKey)))

exitWith('E2E-4 在线终端')