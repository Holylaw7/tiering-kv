import { describe, expect, it } from 'vitest'
import {
  buildInfoSections,
  countSectionMatches,
  filterSectionEntries,
  formatSectionJson,
  formatSectionKvText
} from '@/utils/infoSections'

describe('infoSections helper', () => {
  it('orders Tiering-KV extended sections before core sections and drops empty ones', () => {
    const groups = buildInfoSections({
      default: {},
      Server: { tieringkv_version: '5.2.0', tcp_port: '6379' },
      EmptySection: {},
      Stats: { total_keys: '42' },
      Persistence: { rdb_last_bgsave_status: 'ok' },
      Vector: { vector_count: '128' },
      CustomMetric: { custom_flag: '1' },
      Tiering: { tiering_memtable_used_bytes: '1024', tiering_hot_keys: '10' }
    })

    expect(groups.map((g) => g.name)).toEqual([
      'Tiering',
      'Persistence',
      'Vector',
      'CustomMetric',
      'Server',
      'Stats'
    ])
    expect(groups[0]).toMatchObject({ name: 'Tiering', label: '分层存储', extended: true })
    expect(groups[4]).toMatchObject({ name: 'Server', label: '服务端', extended: false })
  })

  it('filters entries case-insensitively across keys and values and counts matches', () => {
    const [tiering] = buildInfoSections({
      Tiering: {
        tiering_memtable_used_bytes: '2048',
        tiering_cold_sstable_count: '4',
        tiering_hot_keys: '128'
      }
    })

    expect(filterSectionEntries(tiering, '')).toHaveLength(3)
    expect(filterSectionEntries(tiering, 'SSTABLE')).toEqual([
      { key: 'tiering_cold_sstable_count', value: '4' }
    ])
    expect(filterSectionEntries(tiering, '2048')).toEqual([
      { key: 'tiering_memtable_used_bytes', value: '2048' }
    ])
    expect(countSectionMatches(tiering, 'nonexistent')).toBe(0)
  })

  it('formats active section entries to INFO text and pretty JSON', () => {
    const [persistence] = buildInfoSections({
      Persistence: {
        rdb_bgsave_in_progress: '0',
        rdb_last_bgsave_status: 'ok'
      }
    })

    expect(formatSectionKvText(persistence)).toBe(
      '# Persistence\nrdb_bgsave_in_progress:0\nrdb_last_bgsave_status:ok'
    )
    expect(JSON.parse(formatSectionJson(persistence))).toEqual({
      Persistence: {
        rdb_bgsave_in_progress: '0',
        rdb_last_bgsave_status: 'ok'
      }
    })
  })
})
