/** F3 分阶段断言（由 f3-db-outage.ps1 调度）。 */
import { api, check, CONSOLE } from './lib.mjs'

const stage = process.argv[2]

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

if (stage === 'baseline') {
  const res = await api('/auth/login', { method: 'POST', body: { username: 'admin', password: 'admin123' } })
  check('断连前登录正常（基线）', res.status === 200, 'status=' + res.status)
} else if (stage === 'during') {
  const res = await loginWithTimeout(20000)
  check('DB 断连时请求未无限挂起（20s 内返回）', res.aborted === false, 'elapsed=' + res.elapsed + 'ms')
  check('返回 503（依赖不可用），而不是 500/超时', res.status === 503, 'status=' + res.status)
  check('响应耗时受连接超时约束（< 20s）', res.elapsed < 20000, 'elapsed=' + res.elapsed + 'ms')
  const leak = /jdbc:|com\.mysql|HikariPool|SQLException|at io\.tieringkv|password=/i
  check('错误信息不泄漏 SQL/凭据/堆栈', !leak.test(res.text), res.text.replace(/\s+/g, ' ').slice(0, 90))
  check('错误信息可理解且不含内部术语',
    res.text.includes('数据库暂不可用') && !/EntityManager|Hibernate|JPA/.test(res.text),
    res.text.replace(/\s+/g, ' ').slice(0, 90))
} else if (stage === 'after') {
  let recovered = null
  for (let i = 0; i < 10; i++) {
    recovered = await loginWithTimeout(15000)
    if (recovered.status === 200) break
    await new Promise((r) => setTimeout(r, 1500))
  }
  check('不重启控制面时连接池自行恢复（登录 200）',
    recovered?.status === 200, 'status=' + recovered?.status + ' elapsed=' + recovered?.elapsed + 'ms')
}

setTimeout(() => process.exit(0), 100)