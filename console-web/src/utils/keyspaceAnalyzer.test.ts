import { describe, expect, it } from 'vitest'
import { buildPrefixChartOption, buildTtlChartOption, formatBigKeySize } from './keyspaceAnalyzer'

describe('keyspaceAnalyzer utils', () => {
  it('formats byte and collection big-key units', () => {
    expect(formatBigKeySize(512, 'bytes')).toBe('512 B')
    expect(formatBigKeySize(2048, 'bytes')).toBe('2.00 KB')
    expect(formatBigKeySize(1500, 'fields')).toBe('1,500 fields')
    expect(formatBigKeySize(42, 'items')).toBe('42 items')
  })

  it('builds prefix and ttl chart options without empty crashes', () => {
    const prefixOpt = buildPrefixChartOption([
      { prefix: 'cache:*', keyCount: 80, totalSizeUnits: 4096, sharePercent: 80 },
      { prefix: 'user:*', keyCount: 20, totalSizeUnits: 512, sharePercent: 20 }
    ])
    const yAxis = prefixOpt.yAxis as { data: string[] }
    expect(yAxis.data).toEqual(['user:*', 'cache:*'])

    const ttlOpt = buildTtlChartOption({
      noExpire: 50,
      under1h: 10,
      between1hAnd24h: 25,
      over24h: 15
    })
    const series = ttlOpt.series as Array<{ data: Array<{ value: number }> }>
    expect(series[0].data.map((d) => d.value)).toEqual([50, 10, 25, 15])
  })
})
