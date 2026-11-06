import type { SstableTable } from '@/api/types'

export type InventorySortKey = 'id' | 'keys' | 'bytes'
export type SortOrder = 'asc' | 'desc'

/**
 * SSTable 清单排序（TD-105）。
 *
 * <p>只对数值列排序：id / keys / bytes。键范围是**字节序**语义，
 * 前端不得按数值或本地化顺序重排，因此这里刻意不提供 key 排序。
 * 排序基于副本，不修改入参。
 */
export function sortTables(
  tables: readonly SstableTable[],
  key: InventorySortKey,
  order: SortOrder,
): SstableTable[] {
  const direction = order === 'asc' ? 1 : -1
  return [...tables].sort((a, b) => (a[key] - b[key]) * direction)
}

/** 分页切片（页码从 1 开始，越界返回空数组）。 */
export function paginate<T>(rows: readonly T[], page: number, pageSize: number): T[] {
  if (pageSize <= 0) return []
  const start = (Math.max(1, page) - 1) * pageSize
  return rows.slice(start, start + pageSize)
}

/** 总页数（空列表返回 1，避免分页器显示 0 页）。 */
export function pageCount(total: number, pageSize: number): number {
  if (pageSize <= 0) return 1
  return Math.max(1, Math.ceil(total / pageSize))
}
