import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TrendChartsGrid from './TrendChartsGrid.vue'
import type { LiveMetrics, TrendPoint } from '@/api/types'

const ChartPanelStub = {
  name: 'ChartPanel',
  props: ['title', 'hint', 'option'],
  template: '<div class="chart-stub" :data-title="title" :data-hint="hint" />'
}

const sampleTrends: TrendPoint[] = [
  {
    time: '2026-11-27T12:00:00',
    qps: 1.5,
    avgLatencyMs: 0.4,
    usedMemory: 1024,
    maxMemory: 8192,
    totalKeys: 100,
    connections: 5,
    errors: 0
  },
  {
    time: '2026-11-27T12:00:05',
    qps: 4.2,
    avgLatencyMs: 7.5,
    usedMemory: 2048,
    maxMemory: 8192,
    totalKeys: 150,
    connections: 8,
    errors: 1
  }
]

describe('TrendChartsGrid', () => {
  it('computes all 4 ECharts options and latency summary hint when online', () => {
    const wrapper = mount(TrendChartsGrid, {
      props: {
        trends: sampleTrends,
        live: { p50Millis: 0.25, p95Millis: 1.15, latencyMaxMillis: 5.5 } as LiveMetrics,
        offline: false
      },
      global: {
        stubs: { ChartPanel: ChartPanelStub }
      }
    })

    const charts = wrapper.findAllComponents(ChartPanelStub)
    expect(charts).toHaveLength(4)
    expect(charts[1].props('hint')).toContain('P50 0.25 · P95 1.15 · Max 5.50 ms')

    const qpsOpt = charts[0].props('option') as {
      xAxis: { data: string[] }
      yAxis: { max: number }
    }
    expect(qpsOpt.xAxis.data).toEqual(['12:00:00', '12:00:05'])
    expect(qpsOpt.yAxis.max).toBe(5)

    const latOpt = charts[1].props('option') as {
      tooltip: { valueFormatter: (v: number) => string }
      yAxis: { max: number; axisLabel: { formatter: (v: number) => string } }
    }
    expect(latOpt.yAxis.max).toBe(10)
    expect(latOpt.tooltip.valueFormatter(1.2345)).toBe('1.234 ms')
    expect(latOpt.yAxis.axisLabel.formatter(2)).toBe('2.00')

    const memOpt = charts[2].props('option') as {
      yAxis: Array<{ axisLabel: { formatter?: (v: number) => string } }>
    }
    expect(memOpt.yAxis[0].axisLabel.formatter?.(1024)).toContain('1')
  })

  it('handles empty trends and offline latency hint cleanly', () => {
    const wrapper = mount(TrendChartsGrid, {
      props: {
        trends: [],
        live: undefined,
        offline: true
      },
      global: {
        stubs: { ChartPanel: ChartPanelStub }
      }
    })

    const charts = wrapper.findAllComponents(ChartPanelStub)
    expect(charts[1].props('hint')).toBe('平均耗时 (ms)')
    const qpsOpt = charts[0].props('option') as { yAxis: { max?: number } }
    expect(qpsOpt.yAxis.max).toBeUndefined()
  })
})
