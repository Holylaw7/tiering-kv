<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'
import type { EChartsOption } from 'echarts'
import { multiModelApi } from '@/api'
import type { TimeSeriesKeysView, TimeSeriesRangeView } from '@/api/types'
import { formatTsAxisLabel } from '@/utils/multimodel'
import ChartPanel from '@/components/ChartPanel.vue'
import EmptyState from '@/components/EmptyState.vue'

const props = defineProps<{
  instanceId?: number
  tsKeysInfo: TimeSeriesKeysView | null
  canOperate: boolean
}>()

const emit = defineEmits<{
  (e: 'refresh'): void
}>()

const tsQueryForm = ref({
  key: '',
  from: '-inf',
  to: '+inf',
  aggregation: '',
  bucketMs: 60_000,
  count: 200
})
const tsQuerying = ref(false)
const tsRange = ref<TimeSeriesRangeView | null>(null)
const tsAddForm = ref({
  key: '',
  timestamp: '',
  value: 0
})
const tsAdding = ref(false)

const tsChartOption = computed<EChartsOption>(() => {
  const points = tsRange.value?.points ?? []
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 48, right: 20, top: 20, bottom: 32 },
    xAxis: {
      type: 'category',
      data: points.map((p) => formatTsAxisLabel(p.timestamp)),
      axisLabel: { color: '#94a3b8', fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: '#94a3b8', fontSize: 11 },
      splitLine: { lineStyle: { color: 'rgba(148,163,184,0.14)' } }
    },
    series: [
      {
        name: tsRange.value?.key || 'value',
        type: 'line',
        smooth: true,
        showSymbol: points.length <= 40,
        data: points.map((p) => p.value),
        areaStyle: { opacity: 0.12 },
        lineStyle: { width: 2 }
      }
    ]
  }
})

watch(
  () => props.instanceId,
  () => {
    tsRange.value = null
    tsQueryForm.value.key = ''
  }
)

watch(
  () => props.tsKeysInfo,
  async (ts) => {
    if (ts?.supported && ts.series.length > 0 && !tsQueryForm.value.key) {
      tsQueryForm.value.key = ts.series[0].key
      tsAddForm.value.key = ts.series[0].key
      await queryTimeSeries()
    }
  },
  { immediate: true }
)

async function selectSeries(key: string) {
  tsQueryForm.value.key = key
  tsAddForm.value.key = key
  await queryTimeSeries()
}

async function queryTimeSeries() {
  if (!props.instanceId) return
  const f = tsQueryForm.value
  if (!f.key.trim()) {
    ElMessage.warning('请输入或选择时序键名')
    return
  }
  tsQuerying.value = true
  try {
    tsRange.value = await multiModelApi.queryTimeSeriesRange({
      instanceId: props.instanceId,
      key: f.key.trim(),
      from: f.from.trim() || '-inf',
      to: f.to.trim() || '+inf',
      aggregation: f.aggregation || undefined,
      bucketMs: f.aggregation ? f.bucketMs : undefined,
      count: f.count
    })
  } finally {
    tsQuerying.value = false
  }
}

async function submitAddTsPoint() {
  if (!props.instanceId) return
  const f = tsAddForm.value
  if (!f.key.trim()) {
    ElMessage.warning('请输入时序键名')
    return
  }
  const tsVal = f.timestamp.trim() ? Number(f.timestamp.trim()) : undefined
  if (tsVal !== undefined && !Number.isInteger(tsVal)) {
    ElMessage.warning('时间戳必须为整数毫秒，留空则使用当前时间')
    return
  }
  tsAdding.value = true
  try {
    await multiModelApi.addTimeSeriesPoint({
      instanceId: props.instanceId,
      key: f.key.trim(),
      timestamp: tsVal,
      value: f.value
    })
    ElMessage.success('采样点已写入')
    tsQueryForm.value.key = f.key.trim()
    emit('refresh')
    await queryTimeSeries()
  } finally {
    tsAdding.value = false
  }
}
</script>

<template>
  <div class="split-grid">
    <div class="sub-card">
      <h4 class="sub-title">已发现时序键（TS.MRANGE）</h4>
      <el-table :data="tsKeysInfo?.series ?? []" size="small" max-height="260">
        <template #empty>
          <EmptyState title="暂无时序数据" description="可在下方直接写入第一个时序采样点。" />
        </template>
        <el-table-column prop="key" label="键名">
          <template #default="{ row }">
            <el-button link type="primary" @click="selectSeries(row.key)">
              <span class="mono">{{ row.key }}</span>
            </el-button>
          </template>
        </el-table-column>
        <el-table-column prop="length" label="点数" width="68" align="right" />
        <el-table-column label="最新值" width="88" align="right">
          <template #default="{ row }">
            <span class="mono">{{ row.latestValue ?? '—' }}</span>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="canOperate" class="inline-form">
        <h4 class="sub-title" style="margin-top: 14px">写入采样点（TS.ADD）</h4>
        <el-form label-width="72px" size="small" @submit.prevent>
          <el-form-item label="键名">
            <el-input v-model="tsAddForm.key" placeholder="例如 ts:cpu:node1" />
          </el-form-item>
          <el-form-item label="时间戳">
            <el-input v-model="tsAddForm.timestamp" placeholder="毫秒时间戳（留空自动取当前）" />
          </el-form-item>
          <el-form-item label="数值">
            <el-input-number v-model="tsAddForm.value" :precision="3" :step="1" style="width: 100%" />
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              :loading="tsAdding"
              :disabled="tsKeysInfo?.supported === false"
              @click="submitAddTsPoint"
            >
              <el-icon><Plus /></el-icon> 写入数据点
            </el-button>
          </el-form-item>
        </el-form>
      </div>
    </div>

    <div class="sub-card">
      <h4 class="sub-title">范围查询与桶降采样（TS.RANGE / TS.REDUCE）</h4>
      <div class="query-bar wrap">
        <el-input v-model="tsQueryForm.key" placeholder="时序键名" style="width: 180px" />
        <el-input v-model="tsQueryForm.from" placeholder="起始 (-inf)" style="width: 112px" />
        <el-input v-model="tsQueryForm.to" placeholder="截止 (+inf)" style="width: 112px" />
        <el-select v-model="tsQueryForm.aggregation" placeholder="原始采样" clearable style="width: 118px">
          <el-option value="AVG" label="AVG 均值" />
          <el-option value="SUM" label="SUM 求和" />
          <el-option value="MIN" label="MIN 最小" />
          <el-option value="MAX" label="MAX 最大" />
          <el-option value="COUNT" label="COUNT 计数" />
          <el-option value="FIRST" label="FIRST 首点" />
          <el-option value="LAST" label="LAST 末点" />
        </el-select>
        <el-input-number
          v-if="tsQueryForm.aggregation"
          v-model="tsQueryForm.bucketMs"
          :min="1000"
          :step="10000"
          style="width: 132px"
        />
        <el-button
          type="primary"
          :loading="tsQuerying"
          :disabled="tsKeysInfo?.supported === false"
          @click="queryTimeSeries"
        >
          <el-icon><Search /></el-icon> 查询
        </el-button>
      </div>

      <div v-if="tsRange" class="result-meta mono">
        总点数 {{ tsRange.totalLen }} · 当前展示 {{ tsRange.points.length }} 点
        <template v-if="tsRange.reducedValue !== undefined && tsRange.reducedValue !== null">
          · 全序列 {{ tsRange.aggregation }} = {{ Number(tsRange.reducedValue).toFixed(4) }}
        </template>
      </div>

      <ChartPanel
        title="时序趋势曲线"
        :hint="tsRange?.aggregation ? `桶聚合 ${tsRange.aggregation} (${tsRange.bucketMs}ms)` : '原始采样点'"
        :option="tsChartOption"
        height="240px"
        style="margin-top: 10px"
      />
    </div>
  </div>
</template>

<style scoped>
.split-grid {
  display: grid;
  grid-template-columns: minmax(260px, 340px) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

@media (max-width: 1024px) {
  .split-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

.sub-card {
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
  background: var(--surface-2);
  padding: 14px;
}

.sub-title {
  margin: 0 0 10px;
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text-1);
}

.query-bar {
  display: flex;
  align-items: center;
  gap: 8px;
}

.query-bar.wrap {
  flex-wrap: wrap;
}

.result-meta {
  margin-top: 8px;
  font-size: var(--text-xs);
  color: var(--text-3);
}

.inline-form {
  border-top: 1px solid var(--line-soft);
  margin-top: 14px;
}
</style>
