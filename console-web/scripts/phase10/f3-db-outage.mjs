/**
 * F3 数据库断连（隔离实例：dev-mysql.ps1 启动的专用 MySQL，datadir 在 %LOCALAPPDATA%\tieringkv-mysql）。
 *
 * 验证顺序（评审要求）：
 *  1. 停止 MySQL
 *  2. 请求 DB 依赖接口（登录）
 *  3. 请求必须返回（不无限挂起），且错误可理解、不泄漏 SQL/凭据/堆栈
 *  4. 恢复 MySQL
 *  5. **不重启控制面**，再次请求 → 连接池自行恢复
 *  6. 最后才做健康检查
 */
import { execSync } from 'node:child_process'
import { api, check, CONSOLE } from './lib.mjs'

const REPO = 'E:\\学习\\Mini-redis项目\\tiering-kv'
const MYSQL_PORT = 3307

function portOwner(port) {
  let out = ''
  try {
    out = execSync(`netstat -ano | findstr :${port}`, { encoding: 'utf8' })
  } catch {
    return null // findstr 无匹配时返回非 0
  }
  const line = out.split('\n').find((l) => l.includes('LISTENING'))
  if (!line) return null
  return Number(line.trim().split(/\s+/).pop())
}

function stopMysql() {
  const pid = portOwner(MYSQL_PORT)
  if (!pid) throw new Error('未找到 MySQL 监听进程')
  execSync(`taskkill /F /PID ${pid}`, { stdio: 'ignore' })
  return pid
}

function startMysql() {
  execSync(
    `powershell -NoProfile -ExecutionPolicy Bypass -File "${REPO}\\scripts\\dev-mysql.ps1" -Port ${MYSQL_PORT}`,
    { stdio: 'ignore', timeout: 120000 }
  )
}

async function loginWithTimeout(ms) {
  const started = Date.now()
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), ms)
  try {
    const res = await fetch(CONSOLE + '/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Origin: 'http://127.0.0.1:5173' },
      body: JSON.stringify({ username: 'admin', password: 'admin123' }),
      signal: controller.signal
    })
    const text = await res.text()
    return { status: res.status, text, elapsed: Date.now() - started, aborted: false }
  } catch (err) {
    return { status: 0, text: String(err), elapsed: Date.now() - started, aborted: true }
  } finally {
    clearTimeout(timer)
  }
}

// 0) 基线
const before = await api('/auth/login', { method: 'POST', body: { username: 'admin', password: 'admin123' } })
check('断连前登录正常（基线）', before.status === 200, 'status=' + before.status)

let pid = null
try {
  // 1) 停止专用 MySQL
  pid = stopMysql()
  await new Promise((r) => setTimeout(r, 1500))
  check('专用 MySQL 已停止', pid !== null, 'pid=' + pid)

  // 2) + 3) DB 依赖请求必须快速失败且信息可读
  const during = await loginWithTimeout(20000)
  check('DB 断连时请求未无限挂起（20s 内返回）', during.aborted === false,
    'elapsed=' + during.elapsed + 'ms')
  check('返回 5xx 而不是超时/挂起', during.status >= 500, 'status=' + during.status)
  check('响应耗时受连接超时约束（< 20s）', during.elapsed < 20000, 'elapsed=' + during.elapsed + 'ms')

  const leakPattern = /jdbc:|com\.mysql|HikariPool|SQLException|at io\.tieringkv|password=/i
  check('错误信息不泄漏 SQL/凭据/堆栈', !leakPattern.test(during.text),
    during.text.replace(/\s+/g, ' ').slice(0, 90))

  // 4) 恢复 MySQL
  startMysql()
  check('专用 MySQL 已恢复', portOwner(MYSQL_PORT) !== null)

  // 5) 不重启控制面，验证连接池自愈
  let recovered = null
  for (let i = 0; i < 10; i++) {
    recovered = await loginWithTimeout(15000)
    if (recovered.status === 200) break
    await new Promise((r) => setTimeout(r, 1500))
  }
  check('不重启控制面时连接池自行恢复（登录 200）',
    recovered?.status === 200, 'status=' + recovered?.status + ' elapsed=' + recovered?.elapsed + 'ms')

  // 6) 健康检查
  const health = await fetch('http://127.0.0.1:8080/actuator/health').then((r) => r.status).catch(() => 0)
  check('控制面 health 端点可用', health === 200, 'status=' + health)
} finally {
  if (portOwner(MYSQL_PORT) === null) {
    try { startMysql() } catch { /* 交给报告标注 */ }
  }
}

setTimeout(() => process.exit(0), 100)