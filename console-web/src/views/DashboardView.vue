<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { dashboardApi, instanceApi } from '@/api'
import type { OverviewView, TrendPoint } from '@/api/types'
import { useInstanceStore } from '@/stores/instance'
import { formatBytes, formatNumber, formatPercent, formatUptime } from '@/utils/format'
import StatCard from '@/components/StatCard.vue'
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
const memoryRatio = computed(() => {
  const current = live.value
  if (!current || !current.maxMemory) return 0
  return current.usedMemory / current.maxMemory
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

/* 图表视觉：细线、无点、弱网格；面积填充控制在 12% 以内 */
const AXIS_LABEL = { fontSize: 10.5, color: '#6b7784' } as const
const AXIS_LINE = { lineStyle: { color: '#212a34' } } as const
const SPLIT_LINE = { lineStyle: { color: '#212a34', type: 'dashed' as const } } as const

function areaGradient(color: string) {
  return {
    type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
    colorStops: [
      { offset: 0, color: color.replace('1)', '0.14)') },
      { offset: 1, color: color.replace('1)', '0)') }
    ]
  }
}

const qpsOption = computed<any>(() => ({
  backgroundColor: 'transparent',
  grid: { left: 52, right: 20, top: 18, bottom: 26 },
  tooltip: { trigger: 'axis', backgroundColor: '#172029', borderColor: '#2d3742', textStyle: { fontSize: 12 } },
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
      lineStyle: { width: 1.5, color: '#4c8dff' },
      itemStyle: { color: '#4c8dff' },
      areaStyle: { color: areaGradient('rgba(76,141,255,1)') }
    }
  ]
}))

const latencyOption = computed<any>(() => ({
  backgroundColor: 'transparent',
  grid: { left: 52, right: 20, top: 18, bottom: 26 },
  tooltip: {
    trigger: 'axis',
    backgroundColor: '#172029',
    borderColor: '#2d3742',
    textStyle: { fontSize: 12 },
    valueFormatter: (value: number) => Number(value).toFixed(3) + ' ms'
  },
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
      lineStyle: { width: 1.5, color: '#d9a441' },
      itemStyle: { color: '#d9a441' },
      areaStyle: { color: areaGradient('rgba(217,164,65,1)') }
    }
  ]
}))

const memoryOption = computed<any>(() => ({
  backgroundColor: 'transparent',
  grid: { left: 66, right: 56, top: 14, bottom: 26 },
  tooltip: {
    trigger: 'axis',
    backgroundColor: '#172029',
    borderColor: '#2d3742',
    textStyle: { fontSize: 12 }
  },
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
    {
      type: 'value',
      axisLabel: AXIS_LABEL,
      splitLine: { show: false },
      axisLine: { show: false }
    }
  ],
  series: [
    {
      name: '已用内存',
      type: 'line',
      smooth: true,
      showSymbol: false,
      data: trends.value.map((item) => item.usedMemory),
      lineStyle: { width: 1.5, color: '#4c8dff' },
      itemStyle: { color: '#4c8dff' }
    },
    {
      name: '键总数',
      type: 'line',
      yAxisIndex: 1,
      smooth: true,
      showSymbol: false,
      data: trends.value.map((item) => item.totalKeys),
      lineStyle: { width: 1.5, color: '#3fb950', type: 'dashed' },
      itemStyle: { color: '#3fb950' }
    }
  ]
}))

const instances = computed(() => overview.value?.instances ?? instanceStore.list)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-header">
      <div>
        <h2 class="page-title">运行总览</h2>
        <p class="page-subtitle">
          实时读取内核 INFO 指标，采集周期 5 秒；当前实例
          <span class="mono">{{ instanceStore.selected?.host }}:{{ instanceStore.selected?.port }}</span>
        </p>
      </div>
      <div class="actions">
        <el-select v-model="minutes" size="small" style="width: 110px">
          <el-option :value="5" label="近 5 分钟" />
          <el-option :value="30" label="近 30 分钟" />
          <el-option :value="60" label="近 1 小时" />
          <el-option :value="180" label="近 3 小时" />
        </el-select>
        <el-switch v-model="autoRefresh" active-text="自动刷新" size="small" />
        <el-button size="small" @click="load()">
          <el-icon><Refresh /></el-icon>
        </el-button>
      </div>
    </div>

    <div class="stat-grid">
      <StatCard label="QPS" :value="formatNumber(live?.qps, 1)" hint="服务端统计吞吐" />
      <StatCard
        label="平均延迟"
        :value="(live?.avgLatencyMs ?? 0).toFixed(3) + ' ms'"
        hint="命令执行平均耗时"
      />
      <StatCard label="连接数" :value="formatNumber(live?.connections)" hint="当前客户端连接" />
      <StatCard label="键总数" :value="formatNumber(live?.totalKeys)" hint="db0 键空间" />
      <StatCard
        label="内存占用"
        :value="formatBytes(live?.usedMemory)"
        :hint="`水位 ${formatPercent(memoryRatio)} / 上限 ${formatBytes(live?.maxMemory)}`"
      />
      <StatCard
        label="告警"
        :value="formatNumber(overview?.firingAlerts)"
        :hint="`今日已恢复 ${overview?.resolvedToday ?? 0} 条`"
        :tone="(overview?.firingAlerts ?? 0) > 0 ? 'danger' : 'default'"
      />
    </div>

    <div class="charts">
      <ChartPanel title="吞吐" hint="QPS" :option="qpsOption" />
      <ChartPanel title="延迟" hint="平均命令耗时 (ms)" :option="latencyOption" />
      <ChartPanel title="内存与键空间" hint="已用内存 / 键总数" :option="memoryOption" class="span-2" height="260px" />
    </div>

    <div class="panel" style="margin-top: 18px">
      <div class="panel-title">纳管实例</div>
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
            <el-tag :type="row.status === 'UP' ? 'success' : row.status === 'DOWN' ? 'danger' : 'info'" size="small">
              {{ row.status }}
            </el-tag>
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
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="probe(row.id)">探测</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.charts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.span-2 {
  grid-column: span 2;
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