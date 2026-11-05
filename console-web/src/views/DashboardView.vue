<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { dashboardApi, instanceApi } from '@/api'
import type { OverviewView, TrendPoint } from '@/api/types'
import { useInstanceStore } from '@/stores/instance'
import { formatBytes, formatNumber, formatPercent, formatUptime } from '@/utils/format'
import ChartPanel from '@/components/ChartPanel.vue'
import EmptyState from '@/components/EmptyState.vue'

const instanceStore = useInstanceStore()
const overview = ref<OverviewView>()
const trends = ref<TrendPoint[]>([])
const minutes = ref(30)
const autoRefresh = ref(true)
const loading = ref(false)
let timer: number | undefined

const live = computed(() => overview.value?.live)
const selected = computed(() => instanceStore.selected)
const memoryRatio = computed(() => {
  const current = live.value
  if (!current || !current.maxMemory) return 0
  return current.usedMemory / current.maxMemory
})
const heapRatio = computed(() => {
  const current = live.value
  if (!current || !current.maxJvmHeap) return 0
  return current.usedMemoryJvm / current.maxJvmHeap
})

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    await instanceStore.load()
    const id = instanceStore.selectedId
    if (!id) {
      overview.value = undefined
      trends.value = []
      return
    }
    const [ov, tr] = await Promise.all([
      dashboardApi.overview(id),
      dashboardApi.trends(id, minutes.value)
    ])
    overview.value = ov
    trends.value = tr
  } catch {
    // 全局拦截器已提示
  } finally {
    loading.value = false
  }
}

async function probe(id: number) {
  await instanceApi.probe(id)
  ElMessage.success('探测完成')
  await load(true)
}

function startTimer() {
  stopTimer()
  if (autoRefresh.value) {
    timer = window.setInterval(() => load(true), 5000)
  }
}

function stopTimer() {
  if (timer) {
    window.clearInterval(timer)
    timer = undefined
  }
}

watch([() => instanceStore.selectedId, minutes], () => load())
watch(autoRefresh, () => startTimer())

onMounted(async () => {
  await load()
  startTimer()
})

onBeforeUnmount(stopTimer)

const times = computed(() => trends.value.map((item) => item.time.slice(11, 19)))
const instances = computed(() => overview.value?.instances ?? instanceStore.list)

const AXIS_LABEL = { fontSize: 11, color: '#9299a3' } as const
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

function areaGradient(color: string) {
  return {
    type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
    colorStops: [
      { offset: 0, color: color.replace('1)', '0.10)') },
      { offset: 1, color: color.replace('1)', '0)') }
    ]
  }
}

const qpsOption = computed<any>(() => ({
  backgroundColor: 'transparent',
  grid: { left: 48, right: 18, top: 12, bottom: 24 },
  tooltip: TOOLTIP,
  xAxis: {
    type: 'category', data: times.value, boundaryGap: false,
    axisLabel: AXIS_LABEL, axisLine: AXIS_LINE, axisTick: { show: false }
  },
  yAxis: { type: 'value', axisLabel: AXIS_LABEL, splitLine: SPLIT_LINE, axisLine: { show: false } },
  series: [
    {
      name: 'QPS',
      type: 'line',
      smooth: true,
      showSymbol: false,
      data: trends.value.map((item) => Number(item.qps.toFixed(2))),
      lineStyle: { width: 1.5, color: '#3b5bdb' },
      itemStyle: { color: '#3b5bdb' },
      areaStyle: { color: areaGradient('rgba(59,91,219,1)') }
    }
  ]
}))

const latencyOption = computed<any>(() => ({
  backgroundColor: 'transparent',
  grid: { left: 48, right: 18, top: 12, bottom: 24 },
  tooltip: { ...TOOLTIP, valueFormatter: (value: number) => Number(value).toFixed(3) + ' ms' },
  xAxis: {
    type: 'category', data: times.value, boundaryGap: false,
    axisLabel: AXIS_LABEL, axisLine: AXIS_LINE, axisTick: { show: false }
  },
  yAxis: { type: 'value', axisLabel: AXIS_LABEL, splitLine: SPLIT_LINE, axisLine: { show: false } },
  series: [
    {
      name: '平均延迟',
      type: 'line',
      smooth: true,
      showSymbol: false,
      data: trends.value.map((item) => Number(item.avgLatencyMs.toFixed(3))),
      lineStyle: { width: 1.5, color: '#b45309' },
      itemStyle: { color: '#b45309' },
      areaStyle: { color: areaGradient('rgba(180,83,9,1)') }
    }
  ]
}))

const memoryOption = computed<any>(() => ({
  backgroundColor: 'transparent',
  grid: { left: 62, right: 52, top: 12, bottom: 24 },
  tooltip: TOOLTIP,
  xAxis: {
    type: 'category', data: times.value, boundaryGap: false,
    axisLabel: AXIS_LABEL, axisLine: AXIS_LINE, axisTick: { show: false }
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
      data: trends.value.map((item) => item.usedMemory),
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
      data: trends.value.map((item) => item.totalKeys),
      lineStyle: { width: 1.5, color: '#18794e', type: 'dashed' },
      itemStyle: { color: '#18794e' }
    }
  ]
}))
</script>
<template>
  <div class="page" v-loading="loading">
    <div class="page-header">
      <div>
        <h2 class="page-title">运行总览</h2>
        <p class="page-subtitle">实时读取内核 INFO 指标（采集周期 5 秒），按所选实例聚合。</p>
      </div>
      <div class="actions">
        <el-select v-model="minutes" size="small" style="width: 108px">
          <el-option :value="5" label="近 5 分钟" />
          <el-option :value="30" label="近 30 分钟" />
          <el-option :value="60" label="近 1 小时" />
          <el-option :value="180" label="近 3 小时" />
        </el-select>
        <el-switch v-model="autoRefresh" size="small" />
        <span class="switch-label">{{ autoRefresh ? '自动刷新' : '已暂停' }}</span>
        <el-button size="small" @click="load()">
          <el-icon><Refresh /></el-icon>
        </el-button>
      </div>
    </div>

    <div class="status-strip">
      <span class="status-item">
        <span class="dot" :class="(selected?.status ?? 'unknown').toLowerCase()"></span>
        <strong>{{ selected?.name ?? '未选择实例' }}</strong>
      </span>
      <span class="status-item mono">{{ selected?.host }}:{{ selected?.port }}</span>
      <span class="status-item">版本 {{ live?.version || selected?.version || '—' }}</span>
      <span class="status-item">运行 {{ formatUptime(live?.uptimeSeconds) }}</span>
      <span class="status-item">连接 {{ live?.connections ?? 0 }} · 活跃 {{ live?.activeRequests ?? 0 }}</span>
      <span class="status-item grow"></span>
      <span class="status-item">
        <span class="dot" :class="(overview?.firingAlerts ?? 0) > 0 ? 'down' : 'up'"></span>
        未恢复告警 {{ overview?.firingAlerts ?? 0 }} 条
      </span>
    </div>

    <div class="metrics">
      <div class="metric">
        <span class="metric-label">QPS</span>
        <span class="metric-value num">{{ formatNumber(live?.qps, 1) }}</span>
        <span class="metric-hint">近 5 秒均摊</span>
      </div>
      <div class="metric">
        <span class="metric-label">平均延迟</span>
        <span class="metric-value num">{{ (live?.avgLatencyMs ?? 0).toFixed(3) }}<small>ms</small></span>
        <span class="metric-hint">命令执行耗时</span>
      </div>
      <div class="metric">
        <span class="metric-label">键总数</span>
        <span class="metric-value num">{{ formatNumber(live?.totalKeys) }}</span>
        <span class="metric-hint">db0 键空间</span>
      </div>
      <div class="metric">
        <span class="metric-label">引擎内存</span>
        <span class="metric-value num">{{ formatBytes(live?.usedMemory) }}</span>
        <span class="metric-hint">水位 {{ formatPercent(memoryRatio) }}</span>
      </div>
      <div class="metric">
        <span class="metric-label">JVM 堆</span>
        <span class="metric-value num">{{ formatBytes(live?.usedMemoryJvm) }}</span>
        <span class="metric-hint">上限 {{ formatBytes(live?.maxJvmHeap) }}</span>
      </div>
      <div class="metric" :class="{ warn: (live?.errors ?? 0) > 0 }">
        <span class="metric-label">错误计数</span>
        <span class="metric-value num">{{ formatNumber(live?.errors) }}</span>
        <span class="metric-hint">累计命令错误</span>
      </div>
    </div>

    <section class="tiering">
      <div class="tiering-head">
        <h3>存储水位</h3>
        <span class="hint">超水位数据由内核迁移至 SSTable 冷层，读路径自动回退</span>
      </div>
      <div class="bars">
        <div class="bar-row">
          <span class="bar-label">引擎内存</span>
          <div class="bar">
            <div class="bar-fill" :style="{ width: Math.min(100, memoryRatio * 100) + '%' }"></div>
          </div>
          <span class="bar-value num">{{ formatPercent(memoryRatio) }}</span>
          <span class="bar-note mono">{{ formatBytes(live?.usedMemory) }} / {{ formatBytes(live?.maxMemory) }}</span>
        </div>
        <div class="bar-row">
          <span class="bar-label">JVM 堆</span>
          <div class="bar">
            <div class="bar-fill jvm" :style="{ width: Math.min(100, heapRatio * 100) + '%' }"></div>
          </div>
          <span class="bar-value num">{{ formatPercent(heapRatio) }}</span>
          <span class="bar-note mono">{{ formatBytes(live?.usedMemoryJvm) }} / {{ formatBytes(live?.maxJvmHeap) }}</span>
        </div>
      </div>
    </section>

    <div class="charts">
      <ChartPanel title="吞吐" hint="QPS" :option="qpsOption" />
      <ChartPanel title="延迟" hint="平均命令耗时 (ms)" :option="latencyOption" />
      <ChartPanel title="内存与键空间" hint="已用内存 / 键总数" :option="memoryOption" class="span-2" height="220px" />
    </div>

    <section class="panel list-panel">
      <div class="panel-title">
        纳管实例
        <span class="hint">共 {{ instances.length }} 个</span>
      </div>
      <el-table :data="instances" size="small">
        <template #empty>
          <EmptyState title="还没有纳管实例" description="在「实例管理」中添加一个 Tiering-KV 实例后，这里会显示它的探测状态。" />
        </template>
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column label="地址" min-width="170">
          <template #default="{ row }">
            <span class="mono">{{ row.host }}:{{ row.port }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span class="state" :class="row.status.toLowerCase()">
              <span class="dot" :class="row.status.toLowerCase()"></span>{{ row.status }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="版本" width="100">
          <template #default="{ row }">
            <span class="mono">{{ row.version || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="运行时长" width="130">
          <template #default>
            {{ formatUptime(live?.uptimeSeconds) }}
          </template>
        </el-table-column>
        <el-table-column prop="lastProbeAt" label="最近探测" width="180" />
        <el-table-column label="操作" width="100" align="right">
          <template #default="{ row }">
            <el-button link size="small" @click="probe(row.id)">探测</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>
<style scoped>
.actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: none;
}

.switch-label {
  font-size: var(--text-sm);
  color: var(--text-2);
}

.status-strip {
  display: flex;
  align-items: center;
  gap: 22px;
  padding-bottom: 14px;
  font-size: var(--text-sm);
  color: var(--text-2);
  flex-wrap: wrap;
}

.status-item {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  white-space: nowrap;
}

.status-item strong {
  font-weight: 600;
  color: var(--text);
}

.grow {
  flex: 1;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--text-3);
  flex: none;
}

.dot.up {
  background: var(--success);
}

.dot.down {
  background: var(--danger);
}

.metrics {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  margin-bottom: 22px;
}

.metric {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 16px 20px 18px;
  border-left: 1px solid var(--line-soft);
  min-width: 0;
}

.metric:first-child {
  border-left: none;
  padding-left: 2px;
}

.metric-label {
  font-size: var(--text-xs);
  font-weight: 600;
  letter-spacing: 0.05em;
  text-transform: uppercase;
  color: var(--text-3);
}

.metric-value {
  font-size: 24px;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--text);
  line-height: 1.2;
}

.metric-value small {
  margin-left: 3px;
  font-size: 12px;
  font-weight: 500;
  color: var(--text-3);
}

.metric-hint {
  font-size: var(--text-xs);
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric.warn .metric-value {
  color: var(--danger);
}

.tiering {
  margin-bottom: 22px;
}

.tiering-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 10px;
}

.tiering-head h3 {
  margin: 0;
  font-size: var(--text-base);
  font-weight: 600;
  color: var(--text);
}

.tiering-head .hint {
  font-size: var(--text-sm);
  color: var(--text-3);
}

.bars {
  display: grid;
  gap: 10px;
  padding: 14px 18px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
}

.bar-row {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr) 56px 190px;
  align-items: center;
  gap: 14px;
}

.bar-label {
  font-size: var(--text-sm);
  color: var(--text-2);
}

.bar {
  height: 6px;
  border-radius: 999px;
  background: var(--surface-3);
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  background: var(--accent);
  border-radius: 999px;
  transition: width 0.3s var(--ease);
}

.bar-fill.jvm {
  background: #6b7a8c;
}

.bar-value {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text);
  text-align: right;
}

.bar-note {
  font-size: 11px;
  color: var(--text-3);
  text-align: right;
}

.charts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 22px;
}

.span-2 {
  grid-column: span 2;
}

.list-panel {
  padding-bottom: 8px;
}

.state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--text-sm);
  color: var(--text-2);
}

.state.up {
  color: var(--success);
}

.state.down {
  color: var(--danger);
}

@media (max-width: 1280px) {
  .metrics {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .metric:nth-child(4) {
    border-left: none;
    padding-left: 2px;
  }

  .metric:nth-child(n + 4) {
    border-top: 1px solid var(--line-soft);
  }

  .bar-row {
    grid-template-columns: 68px minmax(0, 1fr) 52px;
  }

  .bar-note {
    display: none;
  }
}

@media (max-width: 1100px) {
  .charts {
    grid-template-columns: 1fr;
  }

  .span-2 {
    grid-column: span 1;
  }
}
</style>
