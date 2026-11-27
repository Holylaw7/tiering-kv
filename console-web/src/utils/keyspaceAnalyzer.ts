/** 键空间诊断纯函数工具（v6.4.0 D2）：大键尺寸格式化与 ECharts 柱图配置构建。 */
import type { EChartsOption } from 'echarts'
import type { PrefixStat, TtlDistribution } from '@/api/types'
import { formatBytes } from './format'

export function formatBigKeySize(sizeUnits: number, unitLabel: string): string {
  if (unitLabel === 'bytes') {
    return formatBytes(sizeUnits)
  }
  return `${sizeUnits.toLocaleString('zh-CN')} ${unitLabel}`
}

export function buildPrefixChartOption(prefixes: PrefixStat[]): EChartsOption {
  const ordered = [...prefixes].slice(0, 10).reverse()
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 108, right: 24, top: 12, bottom: 24 },
    xAxis: { type: 'value', minInterval: 1 },
    yAxis: {
      type: 'category',
      data: ordered.map((p) => p.prefix),
      axisLabel: { fontFamily: 'monospace', fontSize: 11 }
    },
    series: [
      {
        name: '键数',
        type: 'bar',
        data: ordered.map((p) => p.keyCount),
        barMaxWidth: 18,
        itemStyle: { color: '#3b5bdb', borderRadius: [0, 4, 4, 0] }
      }
    ]
  }
}

export function buildTtlChartOption(ttl: TtlDistribution): EChartsOption {
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 48, right: 16, top: 16, bottom: 32 },
    xAxis: {
      type: 'category',
      data: ['永久未设 TTL', '< 1 小时', '1–24 小时', '> 24 小时']
    },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        name: '键数',
        type: 'bar',
        barMaxWidth: 36,
        data: [
          { value: ttl.noExpire, itemStyle: { color: '#e67700' } },
          { value: ttl.under1h, itemStyle: { color: '#2b8a3e' } },
          { value: ttl.between1hAnd24h, itemStyle: { color: '#3b5bdb' } },
          { value: ttl.over24h, itemStyle: { color: '#5c7cfa' } }
        ]
      }
    ]
  }
}
