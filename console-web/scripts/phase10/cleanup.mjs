/** Phase 10 清理：删除测试实例、测试用户、phase10-* 备份记录与文件。 */
import fs from 'node:fs'
import path from 'node:path'
import { api, login } from './lib.mjs'

const admin = await login('admin', 'admin123')
const token = admin.token

const instances = await api('/instances', { token })
for (const item of instances.json.data) {
  if (!item.name.startsWith('phase10-')) continue
  const res = await api(`/instances/${item.id}`, { method: 'DELETE', token })
  console.log('delete instance', item.name, res.status)
}

const users = await api('/users', { token })
for (const user of users.json.data.items ?? []) {
  if (!user.username.startsWith('phase10-')) continue
  const res = await api(`/users/${user.id}`, { method: 'DELETE', token })
  console.log('delete user', user.username, res.status)
}

const backups = await api('/backups', { token })
const dirPath = path.resolve('../console-server/backups')
for (const record of backups.json.data) {
  const res = await api(`/backups/${record.id}`, { method: 'DELETE', token })
  const file = path.join(dirPath, record.fileName)
  if (fs.existsSync(file)) fs.rmSync(file)
  console.log('delete backup', record.id, res.status, record.fileName)
}

const rules = await api('/alerts/rules', { token })
for (const rule of rules.json.data) {
  if (!rule.name.startsWith('phase10-')) continue
  await api(`/alerts/rules/${rule.id}`, { method: 'DELETE', token })
  console.log('delete rule', rule.name)
}
setTimeout(() => process.exit(0), 200)
