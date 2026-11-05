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
const tiering = computed(() => {
  const current = live.value
  const hot = current?.memtableUsedBytes ?? 0
  const cold = current?.coldDiskUsageBytes ?? 0
  const total = hot + cold
  return {
    hot,
    cold,
    total,
    hotShare: total ? hot / total : 0,
    coldShare: total ? cold / total : 0,
    entries: current?.memtableEntries ?? 0,
    sstables: current?.coldSstableCount ?? 0,
    flushCount: current?.flushCount ?? 0,
    flushBytes: current?.flushBytes ?? 0,
    pending: current?.migrationPending ?? 0,
    migrated: current?.migrationSuccess ?? 0,
    migrateFailed: current?.migrationFailed ?? 0,
    cacheHitRate: current?.cacheHitRate ?? 0,
    cacheHits: current?.cacheHits ?? 0,
    cacheMisses: current?.cacheMisses ?? 0,
    cacheEvictions: current?.cacheEvictions ?? 0,
    hotKeys: current?.hotKeys ?? 0,
    coldEntries: current?.coldEntries ?? 0,
    hotKeyShare: current?.hotKeyShare ?? 0,
    compactionCount: current?.compactionCount ?? 0,
    compactionInputBytes: current?.compactionInputBytes ?? 0,
    compactionOutputBytes: current?.compactionOutputBytes ?? 0,
    compactionLatencyMs: current?.compactionLatencyMs ?? 0
  }
})
/** 三态：接口失败 / 内核未上报 / 已上报（可能为真实的 0） */
const tieringReported = computed(() => live.value?.tieringReported === true)
const instanceOffline = computed(() => !live.value || selected.value?.status === 'DOWN')
/** 实例离线时指标显示为 “—”，避免把“没取到”误读成“为 0” */
const offline = computed(() => !live.value)
const show = (text: string) => (offline.value ? '—' : text)

const tieringBusy = computed(() =>
  (live.value?.flushInProgress ?? 0) > 0 || (live.value?.compactionInProgress ?? 0) > 0
)
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

const AXIS_LABEL = { fontSize: 11, color: '#9299a3', hideOverlap: true } as const

/** 纵轴上界：给峰值留余量，并向上取到 1/2/5×10^n 的整数刻度，避免出现 1,767.37 这种刻度 */
function axisMax(values: number[]): number | undefined {
  const max = Math.max(...values, 0)
  if (!Number.isFinite(max) || max <= 0) return undefined
  const target = max * 1.15
  const magnitude = Math.pow(10, Math.floor(Math.log10(target)))
  const normalized = target / magnitude
  const step = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10
  return step * magnitude
}
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
  yAxis: {
    type: 'value',
    max: axisMax(trends.value.map((item) => Number(item.qps.toFixed(2)))),
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
  yAxis: {
    type: 'value',
    max: axisMax(trends.value.map((item) => Number(item.avgLatencyMs.toFixed(3)))),
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
        <span class="metric-value num">{{ show(formatNumber(live?.qps, 1)) }}</span>
        <span class="metric-hint">{{ offline ? '实例离线' : '近 5 秒均摊' }}</span>
      </div>
      <div class="metric">
        <span class="metric-label">延迟 P95</span>
        <span class="metric-value num">{{ show((live?.p95Millis ?? 0).toFixed(2)) }}<small v-if="!offline">ms</small></span>
        <span class="metric-hint" v-if="!offline">
          平均 {{ (live?.avgLatencyMs ?? 0).toFixed(2) }} · P99 {{ (live?.p99Millis ?? 0).toFixed(2) }} ms
        </span>
        <span class="metric-hint" v-else>实例离线</span>
      </div>
      <div class="metric">
        <span class="metric-label">键总数</span>
        <span class="metric-value num">{{ show(formatNumber(live?.totalKeys)) }}</span>
        <span class="metric-hint">db0 键空间</span>
      </div>
      <div class="metric">
        <span class="metric-label">引擎内存</span>
        <span class="metric-value num">{{ show(formatBytes(live?.usedMemory)) }}</span>
        <span class="metric-hint">{{ offline ? '实例离线' : '水位 ' + formatPercent(memoryRatio) }}</span>
      </div>
      <div class="metric">
        <span class="metric-label">JVM 堆</span>
        <span class="metric-value num">{{ show(formatBytes(live?.usedMemoryJvm)) }}</span>
        <span class="metric-hint">{{ offline ? '实例离线' : '上限 ' + formatBytes(live?.maxJvmHeap) }}</span>
      </div>
      <div class="metric" :class="{ warn: (live?.errors ?? 0) > 0 }">
        <span class="metric-label">错误计数</span>
        <span class="metric-value num">{{ show(formatNumber(live?.errors)) }}</span>
        <span class="metric-hint">累计命令错误</span>
      </div>
    </div>

    <section class="tiering">
      <div class="tiering-head">
        <h3>存储分层</h3>
        <span class="hint">MemTable → Flush → SSTable，数据来自内核 <span class="mono">INFO # Tiering</span></span>
        <span v-if="tieringBusy" class="busy">
          <span class="busy-dot"></span>
          {{ (live?.compactionInProgress ?? 0) > 0 ? 'Compaction 进行中' : 'Flush 进行中' }}
        </span>
      </div>

      <div v-if="instanceOffline && !live" class="tiering-empty is-error">
        实例不可达：未取到 INFO，分层指标不可用。请检查实例状态后重试。
      </div>
      <div v-else-if="!tieringReported" class="tiering-empty">
        当前内核未上报分层指标（INFO 中无 <span class="mono"># Tiering</span> 段），此处不展示推算值。
      </div>

      <template v-else>
        <div class="pipeline">
          <div class="stage">
            <div class="stage-head">
              <span class="stage-name">MemTable</span>
              <span class="stage-tag hot">热层</span>
            </div>
            <div class="stage-value num">{{ formatBytes(tiering.hot) }}</div>
            <div class="stage-meta">
              {{ formatNumber(tiering.entries) }} 个条目 · 上限 {{ formatBytes(live?.memtableMaxBytes) }}
            </div>
          </div>

          <div class="stage-arrow">
            <span class="arrow-label">Flush {{ formatNumber(tiering.flushCount) }} 次</span>
            <span class="arrow"></span>
            <span class="arrow-sub num">{{ formatBytes(tiering.flushBytes) }}</span>
          </div>

          <div class="stage">
            <div class="stage-head">
              <span class="stage-name">SSTable</span>
              <span class="stage-tag cold">冷层</span>
            </div>
            <div class="stage-value num">{{ formatBytes(tiering.cold) }}</div>
            <div class="stage-meta">{{ formatNumber(tiering.sstables) }} 个表 · 占存储 {{ formatPercent(tiering.coldShare) }}</div>
          </div>

          <div class="stage-arrow">
            <span class="arrow-label">迁移 {{ formatNumber(tiering.migrated) }} 次</span>
            <span class="arrow"></span>
            <span class="arrow-sub num">待处理 {{ tiering.pending }}</span>
          </div>

          <div class="stage">
            <div class="stage-head">
              <span class="stage-name">BlockCache</span>
              <span class="stage-tag cache">读缓存</span>
            </div>
            <div class="stage-value num">{{ formatPercent(tiering.cacheHitRate) }}</div>
            <div class="stage-meta">
              命中 {{ formatNumber(tiering.cacheHits) }} · 未命中 {{ formatNumber(tiering.cacheMisses) }}
            </div>
          </div>
        </div>

        <div class="composition">
          <div class="comp-head">
            <span class="comp-title">存储构成</span>
            <span class="comp-note">
              键分布取 SSTable 表条目累加（含历史版本），非去重精确值
            </span>
          </div>

          <div class="comp-row">
            <span class="comp-label">容量</span>
            <div class="comp-bar" :class="{ empty: tiering.total === 0 }">
              <template v-if="tiering.total > 0">
                <div v-if="tiering.hot > 0" class="comp-hot" :style="{ width: Math.max(1, tiering.hotShare * 100) + '%' }"></div>
                <div class="comp-cold" :style="{ width: Math.max(1, tiering.coldShare * 100) + '%' }"></div>
              </template>
            </div>
            <span class="comp-value num">
              <i class="dot hot"></i>{{ formatBytes(tiering.hot) }}
              <i class="dot cold"></i>{{ formatBytes(tiering.cold) }}
            </span>
          </div>

          <div class="comp-row">
            <span class="comp-label">键</span>
            <div class="comp-bar" :class="{ empty: tiering.hotKeys + tiering.coldEntries === 0 }">
              <template v-if="tiering.hotKeys + tiering.coldEntries > 0">
                <div v-if="tiering.hotKeys > 0" class="comp-hot" :style="{ width: Math.max(1, tiering.hotKeyShare * 100) + '%' }"></div>
                <div class="comp-cold" :style="{ width: Math.max(1, (1 - tiering.hotKeyShare) * 100) + '%' }"></div>
              </template>
            </div>
            <span class="comp-value num">
              <i class="dot hot"></i>{{ formatNumber(tiering.hotKeys) }}
              <i class="dot cold"></i>{{ formatNumber(tiering.coldEntries) }}
            </span>
          </div>

          <div class="comp-foot">
            <span>总占用 {{ formatBytes(tiering.total) }}</span>
            <span v-if="tiering.total === 0">实例为空，尚无分层数据</span>
            <span v-else>
              热层占容量 {{ formatPercent(tiering.hotShare) }} · 键总数（去重）{{ formatNumber(live?.totalKeys) }}
            </span>
            <span>Compaction {{ formatNumber(tiering.compactionCount) }} 次 · 淘汰 {{ formatNumber(tiering.cacheEvictions) }}</span>
          </div>
        </div>
      </template>
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

.busy {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  color: var(--warn);
}

.busy-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--warn);
  animation: pulse 1.4s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 0.35; }
  50% { opacity: 1; }
}

.tiering-empty.is-error {
  border-color: #f3cfcf;
  background: var(--danger-soft);
  color: var(--danger);
}

.tiering-empty {
  padding: 16px 18px;
  border: 1px dashed var(--line);
  border-radius: var(--radius);
  font-size: var(--text-sm);
  color: var(--text-3);
  background: var(--surface-2);
}

.pipeline {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 128px minmax(0, 1fr) 128px minmax(0, 1fr);
  align-items: stretch;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  overflow: hidden;
}

.stage {
  padding: 14px 16px 16px;
  min-width: 0;
}

.stage-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.stage-name {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text);
}

.stage-tag {
  padding: 0 6px;
  height: 18px;
  line-height: 18px;
  border-radius: 3px;
  font-size: 10.5px;
  letter-spacing: 0.03em;
}

.stage-tag.hot {
  background: var(--accent-soft);
  color: var(--accent);
}

.stage-tag.cold {
  background: #eef6f1;
  color: var(--success);
}

.stage-tag.cache {
  background: var(--surface-3);
  color: var(--text-2);
}

.stage-value {
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--text);
  line-height: 1.25;
}

.stage-meta {
  margin-top: 3px;
  font-size: 11.5px;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.stage-arrow {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 0 6px;
  border-left: 1px solid var(--line-soft);
  border-right: 1px solid var(--line-soft);
  background: var(--surface-2);
}

.arrow-label {
  font-size: 10.5px;
  color: var(--text-3);
  white-space: nowrap;
}

.arrow {
  position: relative;
  width: 52px;
  height: 1px;
  background: var(--line-strong);
}

.arrow::after {
  content: '';
  position: absolute;
  right: -1px;
  top: -3px;
  border-left: 5px solid var(--line-strong);
  border-top: 3.5px solid transparent;
  border-bottom: 3.5px solid transparent;
}

.arrow-sub {
  font-size: 10.5px;
  color: var(--text-3);
  white-space: nowrap;
}

.composition {
  margin-top: 8px;
  padding: 14px 16px 12px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
}

.comp-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.comp-title {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text);
}

.comp-note {
  font-size: 11px;
  color: var(--text-3);
}

.comp-row {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr) 220px;
  align-items: center;
  gap: 14px;
}

.comp-row + .comp-row {
  margin-top: 8px;
}

.comp-label {
  font-size: 11.5px;
  color: var(--text-3);
}

.comp-bar {
  display: flex;
  height: 8px;
  border-radius: 999px;
  overflow: hidden;
  background: var(--surface-3);
}

.comp-bar.empty {
  background: repeating-linear-gradient(90deg, var(--surface-3) 0 6px, transparent 6px 12px);
}

.comp-hot {
  background: var(--accent);
}

.comp-cold {
  background: #6fa88a;
}

.comp-value {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  font-size: 11.5px;
  color: var(--text-2);
  white-space: nowrap;
}

.comp-value .dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.comp-value .dot.hot {
  background: var(--accent);
  margin-left: 8px;
}

.comp-value .dot.cold {
  background: #6fa88a;
  margin-left: 8px;
}

.comp-value .dot.hot:first-child {
  margin-left: 0;
}

.comp-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--line-soft);
  font-size: 11px;
  color: var(--text-3);
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

  .pipeline {
    grid-template-columns: minmax(0, 1fr) 92px minmax(0, 1fr);
  }

  .stage-arrow:last-of-type {
    display: none;
  }

  .stage:last-of-type {
    grid-column: 1 / -1;
    border-top: 1px solid var(--line-soft);
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