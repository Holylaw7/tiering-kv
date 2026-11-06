/**
 * E2E-6 告警：唯一规则名 + 确定性触发条件（total_keys < 极大阈值恒真）→ 轮询事件 → 确认恢复。
 * 不依赖开发环境的真实负载。
 */
import { api, check, ensureInstance, exitWith, login, waitFor } from './lib.mjs'

const admin = await login('admin', 'admin123')
const token = admin.token

const target = await ensureInstance(token)
const ruleName = 'phase10-alert-' + Date.now()

let ruleId = null
try {
  const created = await api('/alerts/rules', {
    method: 'POST',
    token,
    body: {
      name: ruleName,
      instanceId: target.id,
      metric: 'total_keys',
      operator: 'LT',
      threshold: 1e12,
      durationSeconds: 0,
      level: 'WARN',
      enabled: true
    }
  })
  check('创建告警规则 200', created.status === 200, 'status=' + created.status)
  ruleId = created.json?.data?.id

  const rules = await api('/alerts/rules', { token })
  check('规则列表包含新规则', rules.json?.data?.some?.((r) => r.id === ruleId))

  // 轮询事件（采集周期 5s，给足 30s）
  const event = await waitFor(async () => {
    const res = await api('/alerts/events?status=FIRING&page=1&size=50', { token })
    return res.json?.data?.items?.find?.((e) => e.ruleId === ruleId) ?? null
  }, { timeoutMs: 30000, intervalMs: 1000, label: '告警事件触发' })

  check('告警事件已触发', Boolean(event?.id), 'eventId=' + event?.id)
  check('事件状态为未恢复（FIRING）', event.status === 'FIRING', 'status=' + event.status)
  check('事件等级与规则一致', event.level === 'WARN', 'level=' + event.level)
  check('事件关联到测试实例', Number(event.instanceId) === Number(target.id), 'instanceId=' + event.instanceId)

  const resolved = await api(`/alerts/events/${event.id}/resolve`, { method: 'POST', token })
  check('确认恢复 200', resolved.status === 200, 'status=' + resolved.status)

  const after = await waitFor(async () => {
    const res = await api('/alerts/events?status=RESOLVED&page=1&size=50', { token })
    return res.json?.data?.items?.find?.((e) => e.id === event.id) ?? null
  }, { timeoutMs: 10000, intervalMs: 500, label: '事件状态变为 RESOLVED' })
  check('事件状态变为 RESOLVED', after.status === 'RESOLVED', 'status=' + after.status)
  check('事件带恢复时间', Boolean(after.resolvedAt), 'resolvedAt=' + after.resolvedAt)
} finally {
  if (ruleId) {
    const removed = await api(`/alerts/rules/${ruleId}`, { method: 'DELETE', token })
    check('清理：删除测试规则 200', removed.status === 200, 'status=' + removed.status)
  }
}

exitWith('E2E-6 告警')