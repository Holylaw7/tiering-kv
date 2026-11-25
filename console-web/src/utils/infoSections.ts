export interface InfoSectionEntry {
  key: string
  value: string
}

export interface InfoSectionGroup {
  name: string
  label: string
  extended: boolean
  entries: InfoSectionEntry[]
}

const EXTENDED_PRIORITY = [
  'Tiering',
  'Persistence',
  'Cluster',
  'Replication',
  'Txn',
  'MVCC',
  'Vector',
  'MultiModel',
  'Backup',
  'Tracing'
] as const

const CORE_SECTIONS = new Set(['Server', 'Clients', 'Memory', 'Stats', 'Keyspace'])
const CORE_ORDER = ['Server', 'Clients', 'Memory', 'Stats', 'Keyspace'] as const

const SECTION_LABELS: Record<string, string> = {
  Tiering: '分层存储',
  Persistence: '持久化',
  Cluster: '集群拓扑',
  Replication: '主从复制',
  Txn: '分布式事务',
  MVCC: '多版本并发',
  Vector: '向量检索',
  MultiModel: '多模型引擎',
  Backup: '物理备份',
  Tracing: '链路追踪',
  Server: '服务端',
  Clients: '客户端',
  Memory: '内存',
  Stats: '统计',
  Keyspace: '键空间',
  CPU: 'CPU',
  Modules: '模块',
  Commandstats: '命令统计',
  Errorstats: '错误统计'
}

function sectionRank(name: string, index: number): number {
  const extIdx = (EXTENDED_PRIORITY as readonly string[]).indexOf(name)
  if (extIdx >= 0) return extIdx
  const coreIdx = (CORE_ORDER as readonly string[]).indexOf(name)
  if (coreIdx >= 0) return 10_000 + coreIdx
  return 1_000 + index
}

/**
 * 将后端返回的 `INFO` 段落映射整理为稳定的只读展示列表：
 * - 跳过空段落；
 * - 优先排序 Tiering-KV 扩展段落，其次其他扩展/标准 Redis 段落，最后为基础核心段落。
 */
export function buildInfoSections(
  raw?: Record<string, Record<string, string>>
): InfoSectionGroup[] {
  if (!raw) return []
  const items: Array<{ rank: number; group: InfoSectionGroup }> = []
  let idx = 0
  for (const [name, kv] of Object.entries(raw)) {
    const trimmedName = name.trim()
    if (!trimmedName || !kv) continue
    const entries: InfoSectionEntry[] = Object.entries(kv)
      .filter(([key]) => key.trim().length > 0)
      .map(([key, value]) => ({ key, value: value ?? '' }))
    if (entries.length === 0) continue
    items.push({
      rank: sectionRank(trimmedName, idx++),
      group: {
        name: trimmedName,
        label: SECTION_LABELS[trimmedName] ?? trimmedName,
        extended: !CORE_SECTIONS.has(trimmedName),
        entries
      }
    })
  }
  items.sort((a, b) => a.rank - b.rank)
  return items.map((item) => item.group)
}

export function filterSectionEntries(
  section: InfoSectionGroup | undefined,
  query: string
): InfoSectionEntry[] {
  if (!section) return []
  const q = query.trim().toLowerCase()
  if (!q) return section.entries
  return section.entries.filter(
    (entry) =>
      entry.key.toLowerCase().includes(q) || entry.value.toLowerCase().includes(q)
  )
}

export function countSectionMatches(section: InfoSectionGroup, query: string): number {
  return filterSectionEntries(section, query).length
}

export function formatSectionKvText(
  section: InfoSectionGroup,
  entries: InfoSectionEntry[] = section.entries
): string {
  const lines = entries.map((entry) => `${entry.key}:${entry.value}`)
  return `# ${section.name}\n${lines.join('\n')}`
}

export function formatSectionJson(
  section: InfoSectionGroup,
  entries: InfoSectionEntry[] = section.entries
): string {
  const payload: Record<string, string> = {}
  for (const entry of entries) {
    payload[entry.key] = entry.value
  }
  return JSON.stringify({ [section.name]: payload }, null, 2)
}
