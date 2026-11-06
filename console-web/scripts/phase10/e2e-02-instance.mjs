/**
 * E2E-2 实例管理：纳管 → 探测 UP → 纳管不可达地址（DOWN）→ 修改备注 → 删除 → 列表核对。
 * 不启停任何真实进程：DOWN 用"无人监听的端口"确定性地构造。
 */
import { api, check, exitWith, login } from './lib.mjs'

const PORT = Number(process.env.E2E_INSTANCE_PORT || 6384)

const admin = await login('admin', 'admin123')
const token = admin.token

const upName = 'phase10-up-' + Date.now()
const downName = 'phase10-down-' + Date.now()

const created = []
try {
  // 1) 可达实例
  const up = await api('/instances', {
    method: 'POST',
    token,
    body: { name: upName, host: '127.0.0.1', port: PORT, remark: 'phase10 e2e', enabled: true }
  })
  check('纳管可达实例 200', up.status === 200, 'status=' + up.status)
  const upId = up.json?.data?.id
  created.push(upId)

  const upProbe = await api(`/instances/${upId}/probe`, { method: 'POST', token })
  check('可达实例探测为 UP', upProbe.json?.data?.status === 'UP', 'status=' + upProbe.json?.data?.status)
  check('探测返回版本号', Boolean(upProbe.json?.data?.version), 'version=' + upProbe.json?.data?.version)

  // 2) 不可达实例（DOWN）
  const down = await api('/instances', {
    method: 'POST',
    token,
    body: { name: downName, host: '127.0.0.1', port: 6399, remark: 'phase10 offline', enabled: true }
  })
  const downId = down.json?.data?.id
  created.push(downId)

  const downProbe = await api(`/instances/${downId}/probe`, { method: 'POST', token })
  check('不可达实例探测为 DOWN', downProbe.json?.data?.status === 'DOWN', 'status=' + downProbe.json?.data?.status)

  const downInfo = await api(`/system/info?instanceId=${downId}`, { token })
  check('离线实例读取 INFO 返回 5xx', downInfo.status >= 500, 'status=' + downInfo.status)

  // 3) 修改备注
  const updated = await api(`/instances/${upId}`, {
    method: 'PUT',
    token,
    body: { name: upName, host: '127.0.0.1', port: PORT, remark: 'phase10 e2e updated', enabled: true }
  })
  check('修改备注 200', updated.status === 200, 'status=' + updated.status)
  const listAfterUpdate = await api('/instances', { token })
  const row = listAfterUpdate.json?.data?.find?.((item) => item.id === upId)
  check('列表反映最新备注', row?.remark === 'phase10 e2e updated', 'remark=' + row?.remark)

  // 4) 删除
  const removed = await api(`/instances/${downId}`, { method: 'DELETE', token })
  check('删除实例 200', removed.status === 200, 'status=' + removed.status)
  const listAfterDelete = await api('/instances', { token })
  check('列表不再包含已删除实例',
    !listAfterDelete.json?.data?.some?.((item) => item.id === downId))
} finally {
  for (const id of created) {
    if (!id) continue
    await api(`/instances/${id}`, { method: 'DELETE', token }).catch(() => null)
  }
}

exitWith('E2E-2 实例管理')