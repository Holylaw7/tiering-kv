import { describe, expect, it } from 'vitest'
import type { SstableTable } from '@/api/types'
import { pageCount, paginate, sortTables } from './inventory'

function table(id: number, keys: number, bytes: number): SstableTable {
  return { id, keys, bytes, firstKey: 'a', lastKey: 'z', firstKeyBase64: '', lastKeyBase64: '' }
}

const rows = [table(3, 100, 2048), table(1, 300, 1024), table(2, 200, 4096)]

describe('sortTables', () => {
  it('按 id 升序且不修改入参', () => {
    const sorted = sortTables(rows, 'id', 'asc')
    expect(sorted.map((r) => r.id)).toEqual([1, 2, 3])
    expect(rows.map((r) => r.id)).toEqual([3, 1, 2])
  })

  it('按键数降序', () => {
    expect(sortTables(rows, 'keys', 'desc').map((r) => r.keys)).toEqual([300, 200, 100])
  })

  it('按大小升序', () => {
    expect(sortTables(rows, 'bytes', 'asc').map((r) => r.bytes)).toEqual([1024, 2048, 4096])
  })

  it('空列表返回空数组', () => {
    expect(sortTables([], 'id', 'asc')).toEqual([])
  })

  it('相同值保持稳定顺序（JS 排序稳定）', () => {
    const same = [table(1, 10, 10), table(2, 10, 10)]
    expect(sortTables(same, 'keys', 'asc').map((r) => r.id)).toEqual([1, 2])
  })
})

describe('paginate', () => {
  const list = Array.from({ length: 120 }, (_, i) => i)

  it('首页返回前 N 条', () => {
    expect(paginate(list, 1, 50)).toHaveLength(50)
    expect(paginate(list, 1, 50)[0]).toBe(0)
  })

  it('末页返回剩余条目', () => {
    expect(paginate(list, 3, 50)).toHaveLength(20)
  })

  it('越界页返回空数组', () => {
    expect(paginate(list, 99, 50)).toEqual([])
  })

  it('非法页大小返回空数组', () => {
    expect(paginate(list, 1, 0)).toEqual([])
  })

  it('页码小于 1 时按第 1 页处理', () => {
    expect(paginate(list, 0, 10)[0]).toBe(0)
  })
})

describe('pageCount', () => {
  it('整除与不整除都向上取整', () => {
    expect(pageCount(120, 50)).toBe(3)
    expect(pageCount(100, 50)).toBe(2)
  })

  it('空数据至少 1 页', () => {
    expect(pageCount(0, 50)).toBe(1)
  })
})
