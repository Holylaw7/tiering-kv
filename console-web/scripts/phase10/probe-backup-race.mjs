/** 观测：同时发两个备份请求时的状态码分布（判定并发守卫是否原子）。 */
import { api, login } from './lib.mjs'

const admin = await login('admin', 'admin123')
const instances = await api('/instances', { token: admin.token })
const target = instances.json.data.find((i) => i.name === 'phase10-kernel')
const id = target.id

const rounds = []
for (let i = 0; i < 5; i++) {
  const [a, b] = await Promise.all([
    api(`/backups?instanceId=${id}`, { method: 'POST', token: admin.token }),
    api(`/backups?instanceId=${id}`, { method: 'POST', token: admin.token })
  ])
  rounds.push([a.status, b.status].sort().join('/'))
  // 等本轮任务结束，避免影响下一轮
  for (let wait = 0; wait < 120; wait++) {
    const list = await api(`/backups?instanceId=${id}`, { token: admin.token })
    if (list.json.data.every((row) => row.status !== 'RUNNING')) break
    await new Promise((r) => setTimeout(r, 250))
  }
}
console.log(JSON.stringify({ rounds }))
