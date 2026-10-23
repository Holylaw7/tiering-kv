<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { dashboardApi, instanceApi } from '@/api'
import type { OverviewView, TrendPoint } from '@/api/types'
import { useInstanceStore } from '@/stores/instance'
import { formatBytes, formatNumber, formatPercent, formatUptime } from '@/utils/format'
import StatCard from '@/components/StatCard.vue'
import ChartPanel from '@/components/ChartPanel.vue'

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

const qpsOption = computed<any>(() => ({
  backgroundColor: 'transparent',
  grid: { left: 48, right: 24, top: 36, bottom: 30 },
  tooltip: { trigger: 'axis' },
  legend: { data: ['QPS'], left: 8, top: 0, textStyle: { fontSize: 11 } },
  xAxis: { type: 'category', data: times.value, boundaryGap: false, axisLabel: { fontSize: 10 } },
  yAxis: { type: 'value', splitLine: { lineStyle: { color: '#232c37' } } },
  series: [
    {
      name: 'QPS',
      type: 'line',
      smooth: true,
      showSymbol: false,
      data: trends.value.map((item) => Number(item.qps.toFixed(2))),
      lineStyle: { width: 2, color: '#35c98b' },
      areaStyle: {
        color: {
          type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: 'rgba(53,201,139,0.35)' },
            { offset: 1, color: 'rgba(53,201,139,0.02)' }
          ]
        }
      }
    }
  ]
}))

const latencyOption = computed<any>(() => ({
  backgroundColor: 'transparent',
  grid: { left: 48, right: 24, top: 36, bottom: 30 },
  tooltip: { trigger: 'axis' },
  xAxis: { type: 'category', data: times.value, boundaryGap: false, axisLabel: { fontSize: 10 } },
  yAxis: { type: 'value', name: 'ms', splitLine: { lineStyle: { color: '#232c37' } } },
  series: [
    {
      name: '平均延迟',
      type: 'line',
      smooth: true,
      showSymbol: false,
      data: trends.value.map((item) => Number(item.avgLatencyMs.toFixed(3))),
      lineStyle: { width: 2, color: '#e6a23c' },
      areaStyle: {
        color: {
          type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: 'rgba(230,162,60,0.3)' },
            { offset: 1, color: 'rgba(230,162,60,0.02)' }
          ]
        }
      }
    }
  ]
}))

const memoryOption = computed<any>(() => ({
  backgroundColor: 'transparent',
  grid: { left: 62, right: 52, top: 40, bottom: 30 },
  tooltip: {
    trigger: 'axis',
    valueFormatter: (value: number) => formatBytes(value)
  },
  legend: { data: ['已用内存', '键总数'], left: 8, top: 0, itemGap: 18, textStyle: { fontSize: 11 } },
  xAxis: { type: 'category', data: times.value, boundaryGap: false, axisLabel: { fontSize: 10 } },
  yAxis: [
    { type: 'value', axisLabel: { formatter: (value: number) => formatBytes(value) }, splitLine: { lineStyle: { color: '#232c37' } } },
    { type: 'value', splitLine: { show: false } }
  ],
  series: [
    {
      name: '已用内存',
      type: 'line',
      smooth: true,
      showSymbol: false,
      data: trends.value.map((item) => item.usedMemory),
      lineStyle: { width: 2, color: '#7c5cff' }
    },
    {
      name: '键总数',
      type: 'bar',
      yAxisIndex: 1,
      barMaxWidth: 10,
      data: trends.value.map((item) => item.totalKeys),
      itemStyle: { color: 'rgba(64,158,255,0.55)', borderRadius: [3, 3, 0, 0] }
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
      <StatCard label="QPS" :value="formatNumber(live?.qps, 1)" hint="服务端统计吞吐" icon="TrendCharts" />
      <StatCard
        label="平均延迟"
        :value="(live?.avgLatencyMs ?? 0).toFixed(3) + ' ms'"
        hint="命令执行平均耗时"
        icon="Timer"
        accent="#e6a23c"
      />
      <StatCard label="连接数" :value="formatNumber(live?.connections)" hint="当前客户端连接" icon="Connection" />
      <StatCard label="键总数" :value="formatNumber(live?.totalKeys)" hint="db0 键空间" icon="Grid" accent="#409eff" />
      <StatCard
        label="内存占用"
        :value="formatBytes(live?.usedMemory)"
        :hint="`水位 ${formatPercent(memoryRatio)} / 上限 ${formatBytes(live?.maxMemory)}`"
        icon="Cpu"
        accent="#7c5cff"
      />
      <StatCard
        label="告警"
        :value="formatNumber(overview?.firingAlerts)"
        :hint="`今日已恢复 ${overview?.resolvedToday ?? 0} 条`"
        icon="Bell"
        accent="#f56c6c"
      />
    </div>

    <div class="charts">
      <ChartPanel title="吞吐趋势" :option="qpsOption" />
      <ChartPanel title="延迟趋势" :option="latencyOption" />
      <ChartPanel title="内存与键空间" :option="memoryOption" class="span-2" height="280px" />
    </div>

    <div class="panel" style="margin-top: 18px">
      <div class="panel-title">纳管实例</div>
      <el-table :data="instances" size="small">
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column label="地址" min-width="170">
          <template #default="{ row }">
            <span class="mono">{{ row.host }}:{{ row.port }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'UP' ? 'success' : row.status === 'DOWN' ? 'danger' : 'info'" size="small" effect="dark">
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
        <el-table-column label="操作" width="110" fixed="right">
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
