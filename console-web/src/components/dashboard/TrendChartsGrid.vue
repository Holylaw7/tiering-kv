<script setup lang="ts">
import { computed } from 'vue'
import type { LiveMetrics, TrendPoint } from '@/api/types'
import { formatBytes } from '@/utils/format'
import ChartPanel from '@/components/ChartPanel.vue'

const props = defineProps<{
  trends: TrendPoint[]
  live?: LiveMetrics
  offline: boolean
}>()

const times = computed(() => props.trends.map((item) => item.time.slice(11, 19)))

const AXIS_LABEL = { fontSize: 11, color: '#9299a3', hideOverlap: true } as const
const AXIS_LINE = { lineStyle: { color: '#e5e7eb' } } as const
const SPLIT_LINE = { lineStyle: { color: '#eceef1' } } as const
const TOOLTIP = {
  trigger: 'axis',
  backgroundColor: '#ffffff',
  borderColor: '#e5e7eb',
  borderWidth: 1,
  padding: [8, 12],
  textStyle: { color: '#202124', fontSize: 12 },
  extraCssText: 'box-shadow: 0 8px 24px rgba(16,24,40,.10); border-radius: 8px;'
} as const

function axisMax(values: number[]): number | undefined {
  const max = Math.max(...values, 0)
  if (!Number.isFinite(max) || max <= 0) return undefined
  const target = max * 1.15
  const magnitude = Math.pow(10, Math.floor(Math.log10(target)))
  const normalized = target / magnitude
  const step = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10
  return step * magnitude
}

function areaGradient(color: string) {
  return {
    type: 'linear',
    x: 0,
    y: 0,
    x2: 0,
    y2: 1,
    colorStops: [
      { offset: 0, color: color.replace('1)', '0.10)') },
      { offset: 1, color: color.replace('1)', '0)') }
    ]
  }
}

const qpsOption = computed<Record<string, unknown>>(() => ({
  backgroundColor: 'transparent',
  grid: { left: 48, right: 18, top: 12, bottom: 24 },
  tooltip: TOOLTIP,
  xAxis: {
    type: 'category',
    data: times.value,
    boundaryGap: false,
    axisLabel: AXIS_LABEL,
    axisLine: AXIS_LINE,
    axisTick: { show: false }
  },
  yAxis: {
    type: 'value',
    max: axisMax(props.trends.map((item) => Number(item.qps.toFixed(2)))),
    axisLabel: AXIS_LABEL,
    splitLine: SPLIT_LINE,
    axisLine: { show: false }
  },
  series: [
    {
      name: 'QPS',
      type: 'line',
      smooth: true,
      showSymbol: false,
      data: props.trends.map((item) => Number(item.qps.toFixed(2))),
      lineStyle: { width: 1.5, color: '#3b5bdb' },
      itemStyle: { color: '#3b5bdb' },
      areaStyle: { color: areaGradient('rgba(59,91,219,1)') }
    }
  ]
}))

const latencyOption = computed<Record<string, unknown>>(() => ({
  backgroundColor: 'transparent',
  grid: { left: 48, right: 18, top: 12, bottom: 24 },
  tooltip: { ...TOOLTIP, valueFormatter: (value: number) => Number(value).toFixed(3) + ' ms' },
  xAxis: {
    type: 'category',
    data: times.value,
    boundaryGap: false,
    axisLabel: AXIS_LABEL,
    axisLine: AXIS_LINE,
    axisTick: { show: false }
  },
  yAxis: {
    type: 'value',
    max: axisMax(props.trends.map((item) => Number(item.avgLatencyMs.toFixed(3)))),
    axisLabel: { ...AXIS_LABEL, formatter: (value: number) => Number(value).toFixed(2) },
    splitLine: SPLIT_LINE,
    axisLine: { show: false }
  },
  series: [
    {
      name: '平均延迟',
      type: 'line',
      smooth: true,
      showSymbol: false,
      data: props.trends.map((item) => Number(item.avgLatencyMs.toFixed(3))),
      lineStyle: { width: 1.5, color: '#b45309' },
      itemStyle: { color: '#b45309' },
      areaStyle: { color: areaGradient('rgba(180,83,9,1)') }
    }
  ]
}))

const memoryOption = computed<Record<string, unknown>>(() => ({
  backgroundColor: 'transparent',
  grid: { left: 62, right: 52, top: 12, bottom: 24 },
  tooltip: TOOLTIP,
  xAxis: {
    type: 'category',
    data: times.value,
    boundaryGap: false,
    axisLabel: AXIS_LABEL,
    axisLine: AXIS_LINE,
    axisTick: { show: false }
  },
  yAxis: [
    {
      type: 'value',
      axisLabel: { ...AXIS_LABEL, formatter: (value: number) => formatBytes(value) },
      splitLine: SPLIT_LINE,
      axisLine: { show: false }
    },
    { type: 'value', axisLabel: AXIS_LABEL, splitLine: { show: false }, axisLine: { show: false } }
  ],
  series: [
    {
      name: '已用内存',
      type: 'line',
      smooth: true,
      showSymbol: false,
      data: props.trends.map((item) => item.usedMemory),
      lineStyle: { width: 1.5, color: '#3b5bdb' },
      itemStyle: { color: '#3b5bdb' },
      areaStyle: { color: areaGradient('rgba(59,91,219,1)') }
    },
    {
      name: '键总数',
      type: 'line',
      yAxisIndex: 1,
      smooth: true,
      showSymbol: false,
      data: props.trends.map((item) => item.totalKeys),
      lineStyle: { width: 1.5, color: '#18794e', type: 'dashed' },
      itemStyle: { color: '#18794e' }
    }
  ]
}))

const activityOption = computed<Record<string, unknown>>(() => ({
  backgroundColor: 'transparent',
  grid: { left: 48, right: 44, top: 16, bottom: 24 },
  tooltip: TOOLTIP,
  legend: {
    top: 0,
    right: 8,
    textStyle: { color: '#626b78', fontSize: 11 }
  },
  xAxis: {
    type: 'category',
    data: times.value,
    boundaryGap: false,
    axisLabel: AXIS_LABEL,
    axisLine: AXIS_LINE,
    axisTick: { show: false }
  },
  yAxis: [
    {
      type: 'value',
      name: '连接',
      nameTextStyle: { color: '#9299a3', fontSize: 10 },
      minInterval: 1,
      axisLabel: AXIS_LABEL,
      splitLine: SPLIT_LINE,
      axisLine: { show: false }
    },
    {
      type: 'value',
      name: '错误',
      nameTextStyle: { color: '#9299a3', fontSize: 10 },
      minInterval: 1,
      axisLabel: AXIS_LABEL,
      splitLine: { show: false },
      axisLine: { show: false }
    }
  ],
  series: [
    {
      name: '活跃连接',
      type: 'line',
      smooth: true,
      showSymbol: false,
      data: props.trends.map((item) => item.connections),
      lineStyle: { width: 1.5, color: '#0f766e' },
      itemStyle: { color: '#0f766e' },
      areaStyle: { color: areaGradient('rgba(15,118,110,1)') }
    },
    {
      name: '累计错误',
      type: 'line',
      yAxisIndex: 1,
      smooth: false,
      showSymbol: false,
      data: props.trends.map((item) => item.errors),
      lineStyle: { width: 1.5, color: '#c62828' },
      itemStyle: { color: '#c62828' }
    }
  ]
}))
</script>

<template>
  <div class="charts">
    <ChartPanel title="吞吐" hint="QPS" :option="qpsOption" />
    <ChartPanel
      title="延迟"
      :hint="
        offline
          ? '平均耗时 (ms)'
          : `P50 ${(live?.p50Millis ?? 0).toFixed(2)} · P95 ${(live?.p95Millis ?? 0).toFixed(2)} · Max ${(live?.latencyMaxMillis ?? 0).toFixed(2)} ms`
      "
      :option="latencyOption"
    />
    <ChartPanel title="内存与键空间" hint="已用内存 / 键总数" :option="memoryOption" />
    <ChartPanel title="连接与错误趋势" hint="活跃连接数 / 累计错误" :option="activityOption" />
  </div>
</template>

<style scoped>
.charts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 22px;
}

@media (max-width: 1100px) {
  .charts {
    grid-template-columns: 1fr;
  }
}
</style>
