/**
 * E2E-7 审计：筛选分页 + CSV 导出（真解析器校验字段，不按逗号切割）。
 * 依赖前置用例产生的操作记录（登录、备份、终端、告警）。
 */
import { api, check, exitWith, login, parseCsv } from './lib.mjs'

const admin = await login('admin', 'admin123')
const token = admin.token

const instances = await api('/instances', { token })
const target = instances.json.data.find((item) => item.name === 'phase10-kernel')

const page = await api('/audit?page=1&size=10', { token })
check('审计分页 200', page.status === 200, 'status=' + page.status)
check('返回总记录数 > 0', Number(page.json?.data?.total) > 0, 'total=' + page.json?.data?.total)
check('分页大小生效', (page.json?.data?.items ?? []).length <= 10,
  'size=' + (page.json?.data?.items ?? []).length)

const first = page.json.data.items[0]
check('记录字段完整（action/username/success/createdAt）',
  Boolean(first.action) && Boolean(first.username) && first.success !== undefined && Boolean(first.createdAt),
  JSON.stringify({ action: first.action, username: first.username, success: first.success }))

// 按 action 过滤（用已知会产生记录的动作）
const loginPage = await api('/audit?action=LOGIN&page=1&size=20', { token })
const allLogin = (loginPage.json?.data?.items ?? []).every((row) => row.action === 'LOGIN')
check('action=LOGIN 过滤生效', allLogin && (loginPage.json?.data?.items ?? []).length > 0,
  'rows=' + (loginPage.json?.data?.items ?? []).length)

// 失败记录：先制造一次被拦截的终端命令（确定性），再验证筛选与内容
await api('/terminal/execute', { method: 'POST', token, body: { instanceId: target?.id ?? 4, command: 'FLUSHALL' } })
const failed = await api('/audit?success=false&page=1&size=20', { token })
const failedRows = failed.json?.data?.items ?? []
check('失败操作已入审计（success=false 有记录）', failedRows.length > 0, 'rows=' + failedRows.length)
check('失败记录一律 success=false', failedRows.every((row) => row.success === false))

// CSV 导出
const csv = await api('/audit/export?action=LOGIN', { token, raw: true })
check('导出返回 200', csv.status === 200, 'status=' + csv.status)
check('Content-Type 为 text/csv', String(csv.headers.get('content-type') || '').includes('text/csv'),
  csv.headers.get('content-type') || '')
check('附件头存在', String(csv.headers.get('content-disposition') || '').includes('audit-logs.csv'),
  csv.headers.get('content-disposition') || '')

const text = csv.buffer.toString('utf8')
check('CSV 带 UTF-8 BOM', csv.buffer[0] === 0xEF && csv.buffer[1] === 0xBB && csv.buffer[2] === 0xBF,
  'head=' + csv.buffer.subarray(0, 3).toString('hex'))

const rows = parseCsv(text)
// 导出的 CSV 面向 Excel，表头为中文；这里按真实表头断言
const header = rows[0] ?? []
check('CSV 表头包含关键列',
  ['时间', '用户', '动作', '结果'].every((name) => header.includes(name)),
  header.join(','))
check('CSV 数据行数 > 1', rows.length > 1, 'rows=' + rows.length)

const actionIndex = header.indexOf('动作')
const allRowsMatch = rows.slice(1).filter((r) => r.length > 1).every((r) => r[actionIndex] === 'LOGIN')
check('CSV 内容与筛选条件一致', allRowsMatch, 'csvRows=' + (rows.length - 1))

// 含逗号/引号的字段也必须被正确解析（否则字段数会错位）
const malformed = rows.slice(1).filter((r) => r.length > 1 && r.length !== header.length)
check('CSV 每行列数与表头一致（引号/逗号处理正确）', malformed.length === 0,
  'malformed=' + malformed.length)

exitWith('E2E-7 审计')