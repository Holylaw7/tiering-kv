/**
 * E2E-1 认证与权限。
 *
 * 约束（Phase 10 评审）：
 * - 改密用**专用测试账号**，不动 admin；
 * - 无论断言成败，finally 里恢复原密码；恢复失败必须报"环境未恢复"而不是 PASS。
 */
import { api, check, exitWith, login, waitFor } from './lib.mjs'

const TEST_USER = 'phase10-user'
const TEST_PASS = 'phase10Pass!1'
const NEXT_PASS = 'phase10Pass!2'

async function ensureUser(adminToken) {
  const list = await api('/users', { token: adminToken })
  const exists = list.json?.data?.some?.((u) => u.username === TEST_USER)
    || list.json?.data?.items?.some?.((u) => u.username === TEST_USER)
  if (exists) return null
  const created = await api('/users', {
    method: 'POST',
    token: adminToken,
    body: { username: TEST_USER, password: TEST_PASS, role: 'VIEWER', enabled: true }
  })
  if (created.status !== 200) throw new Error('创建测试账号失败：' + created.text)
  return created.json.data?.id
}

let restored = true
try {
  const admin = await login('admin', 'admin123')
  check('管理员登录成功', Boolean(admin.token))

  const me = await api('/auth/me', { token: admin.token })
  check('GET /auth/me 返回管理员身份', me.status === 200 && me.json?.data?.username === 'admin')

  await ensureUser(admin.token)

  // 改密（测试账号）
  const changed = await api('/auth/password', {
    method: 'POST',
    token: (await login(TEST_USER, TEST_PASS)).token,
    body: { oldPassword: TEST_PASS, newPassword: NEXT_PASS }
  })
  check('测试账号改密成功', changed.status === 200, 'status=' + changed.status)

  const oldLogin = await api('/auth/login', { method: 'POST', body: { username: TEST_USER, password: TEST_PASS } })
  check('旧口令登录被拒', oldLogin.status === 401, 'status=' + oldLogin.status)

  const newLogin = await api('/auth/login', { method: 'POST', body: { username: TEST_USER, password: NEXT_PASS } })
  check('新口令登录成功', newLogin.status === 200 && Boolean(newLogin.json?.data?.token))
  const viewer = newLogin.json.data.token

  // 权限矩阵
  const forbidden = await api('/keys?instanceId=1', { method: 'PUT', token: viewer, body: { key: 'x', value: 'y' } })
  check('VIEWER 写入键空间被拒 403', forbidden.status === 403, 'status=' + forbidden.status)

  const audit = await api('/audit', { token: viewer })
  check('VIEWER 读取审计被拒 403', audit.status === 403, 'status=' + audit.status)

  const backups = await api('/backups', { token: viewer })
  check('VIEWER 可读备份列表 200', backups.status === 200, 'status=' + backups.status)

  const anon = await api('/dashboard/overview')
  check('匿名访问被拒 401', anon.status === 401, 'status=' + anon.status)

  // 恢复口令（finally 中再做一次，双保险）
} finally {
  try {
    const viewerToken = (await login(TEST_USER, NEXT_PASS)).token
    const back = await api('/auth/password', {
      method: 'POST',
      token: viewerToken,
      body: { oldPassword: NEXT_PASS, newPassword: TEST_PASS }
    })
    check('测试账号口令已恢复', back.status === 200, 'status=' + back.status)
  } catch (err) {
    restored = false
    check('测试账号口令已恢复', false, String(err))
  }
}

if (!restored) {
  console.log('\n环境未恢复：测试账号口令未能还原，请人工检查后再运行其他用例')
  setTimeout(() => process.exit(2), 100)
} else {
  exitWith('E2E-1 认证与权限')
}
