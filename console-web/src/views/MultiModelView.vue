<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Finished, Plus, Refresh, Search } from '@element-plus/icons-vue'
import type { EChartsOption } from 'echarts'
import { multiModelApi } from '@/api'
import type {
  JsonKeysView,
  JsonQueryView,
  TimeSeriesKeysView,
  TimeSeriesRangeView,
  VectorCollectionsView,
  VectorSearchResultView
} from '@/api/types'
import { useInstanceStore } from '@/stores/instance'
import { useAuthStore } from '@/stores/auth'
import {
  formatTsAxisLabel,
  normalizeSimilarityPercent,
  parseVectorText
} from '@/utils/multimodel'
import ChartPanel from '@/components/ChartPanel.vue'
import EmptyState from '@/components/EmptyState.vue'
import PageToolbar from '@/components/PageToolbar.vue'
import StatCard from '@/components/StatCard.vue'
import StatusBadge from '@/components/StatusBadge.vue'

const store = useInstanceStore()
const auth = useAuthStore()

const activeTab = ref<'vector' | 'timeseries' | 'json'>('vector')
const loadingSummary = ref(false)

// ==================== Vector HNSW 状态 ====================
const vectorInfo = ref<VectorCollectionsView | null>(null)
const selectedCollection = ref('default')
const vectorQueryText = ref('0.1, 0.2, 0.3, 0.4')
const vectorTopK = ref(10)
const vectorSearching = ref(false)
const vectorResult = ref<VectorSearchResultView | null>(null)
const vectorCheckpointing = ref(false)

const addVectorVisible = ref(false)
const addVectorSaving = ref(false)
const addVectorForm = ref({
  collection: 'default',
  id: '',
  valuesText: '0.1, 0.2, 0.3, 0.4'
})

// ==================== TimeSeries 状态 ====================
const tsKeysInfo = ref<TimeSeriesKeysView | null>(null)
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

// ==================== JSON Path Explorer 状态 ====================
const jsonKeysInfo = ref<JsonKeysView | null>(null)
const jsonPattern = ref('*')
const jsonQueryForm = ref({
  key: '',
  path: '$'
})
const jsonQuerying = ref(false)
const jsonResult = ref<JsonQueryView | null>(null)
const jsonEditorText = ref('')
const jsonSaving = ref(false)
const jsonDialogVisible = ref(false)
const jsonCreateForm = ref({
  key: '',
  path: '$',
  value: '{\n  "service": "tiering-kv",\n  "version": "6.2.0",\n  "replicas": 3,\n  "tags": ["lsm", "hnsw"]\n}'
})

// ==================== 概览加载 ====================
const anyUnsupported = computed(() => {
  if (activeTab.value === 'vector') return vectorInfo.value?.supported === false
  if (activeTab.value === 'timeseries') return tsKeysInfo.value?.supported === false
  return jsonKeysInfo.value?.supported === false
})

const unsupportedMessage = computed(() => {
  if (activeTab.value === 'vector') return vectorInfo.value?.message
  if (activeTab.value === 'timeseries') return tsKeysInfo.value?.message
  return jsonKeysInfo.value?.message
})

async function loadOverview() {
  const instanceId = store.selectedId
  if (!instanceId) {
    vectorInfo.value = null
    tsKeysInfo.value = null
    jsonKeysInfo.value = null
    return
  }
  loadingSummary.value = true
  try {
    const [vec, ts, js] = await Promise.all([
      multiModelApi.vectorCollections(instanceId).catch(() => null),
      multiModelApi.timeSeriesKeys(instanceId).catch(() => null),
      multiModelApi.jsonKeys(instanceId, jsonPattern.value, 100).catch(() => null)
    ])
    vectorInfo.value = vec
    tsKeysInfo.value = ts
    jsonKeysInfo.value = js

    if (vec?.supported && vec.collections.length > 0) {
      const exists = vec.collections.some((c) => c.name === selectedCollection.value)
      if (!exists) {
        selectedCollection.value = vec.collections[0].name
      }
    }
    if (ts?.supported && ts.series.length > 0 && !tsQueryForm.value.key) {
      tsQueryForm.value.key = ts.series[0].key
      tsAddForm.value.key = ts.series[0].key
      await queryTimeSeries()
    }
    if (js?.supported && js.keys.length > 0 && !jsonQueryForm.value.key) {
      jsonQueryForm.value.key = js.keys[0]
      await queryJson()
    }
  } finally {
    loadingSummary.value = false
  }
}

// ==================== Vector 操作 ====================
function openAddVector() {
  addVectorForm.value = {
    collection: selectedCollection.value || 'default',
    id: '',
    valuesText: vectorQueryText.value || '0.1, 0.2, 0.3, 0.4'
  }
  addVectorVisible.value = true
}

async function submitAddVector() {
  if (!store.selectedId) return
  const f = addVectorForm.value
  if (!f.id.trim()) {
    ElMessage.warning('请填写向量 ID')
    return
  }
  let values: number[]
  try {
    values = parseVectorText(f.valuesText)
  } catch (err: unknown) {
    ElMessage.warning(err instanceof Error ? err.message : '向量格式无效')
    return
  }
  addVectorSaving.value = true
  try {
    await multiModelApi.addVector({
      instanceId: store.selectedId,
      collection: f.collection.trim() || 'default',
      id: f.id.trim(),
      values
    })
    ElMessage.success(`向量 ${f.id.trim()} 已写入集合 ${f.collection.trim() || 'default'}`)
    selectedCollection.value = f.collection.trim() || 'default'
    addVectorVisible.value = false
    await loadOverview()
  } finally {
    addVectorSaving.value = false
  }
}

async function runVectorSearch() {
  if (!store.selectedId) {
    ElMessage.warning('请先选择实例')
    return
  }
  let vector: number[]
  try {
    vector = parseVectorText(vectorQueryText.value)
  } catch (err: unknown) {
    ElMessage.warning(err instanceof Error ? err.message : '查询向量格式无效')
    return
  }
  vectorSearching.value = true
  try {
    vectorResult.value = await multiModelApi.searchVector({
      instanceId: store.selectedId,
      collection: selectedCollection.value.trim() || 'default',
      vector,
      topK: vectorTopK.value
    })
  } finally {
    vectorSearching.value = false
  }
}

async function deleteVectorItem(id: string) {
  if (!store.selectedId) return
  await ElMessageBox.confirm(`确认删除向量「${id}」？`, '删除向量', { type: 'warning' })
  await multiModelApi.deleteVector(store.selectedId, selectedCollection.value, id)
  ElMessage.success('向量已删除')
  await Promise.all([loadOverview(), runVectorSearch()])
}

async function dropCollection(name: string) {
  if (!store.selectedId) return
  await ElMessageBox.confirm(`确认删除整个向量集合「${name}」？该操作不可恢复。`, '删除集合', {
    type: 'warning'
  })
  await multiModelApi.dropVectorCollection(store.selectedId, name)
  ElMessage.success(`集合 ${name} 已删除`)
  vectorResult.value = null
  await loadOverview()
}

async function checkpointCollection() {
  if (!store.selectedId) return
  vectorCheckpointing.value = true
  try {
    await multiModelApi.checkpointVector(store.selectedId, selectedCollection.value || undefined)
    ElMessage.success(`集合 ${selectedCollection.value || '全部'} 物理索引落盘完成`)
  } finally {
    vectorCheckpointing.value = false
  }
}

// ==================== TimeSeries 操作 ====================
async function selectSeries(key: string) {
  tsQueryForm.value.key = key
  tsAddForm.value.key = key
  await queryTimeSeries()
}

async function queryTimeSeries() {
  if (!store.selectedId) return
  const f = tsQueryForm.value
  if (!f.key.trim()) {
    ElMessage.warning('请输入或选择时序键名')
    return
  }
  tsQuerying.value = true
  try {
    tsRange.value = await multiModelApi.queryTimeSeriesRange({
      instanceId: store.selectedId,
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
  if (!store.selectedId) return
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
      instanceId: store.selectedId,
      key: f.key.trim(),
      timestamp: tsVal,
      value: f.value
    })
    ElMessage.success('采样点已写入')
    tsQueryForm.value.key = f.key.trim()
    await Promise.all([loadOverview(), queryTimeSeries()])
  } finally {
    tsAdding.value = false
  }
}

// ==================== JSON Path 操作 ====================
async function refreshJsonKeys() {
  if (!store.selectedId) return
  jsonKeysInfo.value = await multiModelApi.jsonKeys(store.selectedId, jsonPattern.value, 100)
}

async function selectJsonKey(key: string) {
  jsonQueryForm.value.key = key
  jsonQueryForm.value.path = '$'
  await queryJson()
}

async function queryJson() {
  if (!store.selectedId) return
  const key = jsonQueryForm.value.key.trim()
  if (!key) {
    ElMessage.warning('请输入 JSON 键名')
    return
  }
  jsonQuerying.value = true
  try {
    const res = await multiModelApi.getJson(
      store.selectedId,
      key,
      jsonQueryForm.value.path.trim() || '$'
    )
    jsonResult.value = res
    jsonEditorText.value = res.prettyJson ?? res.rawJson ?? ''
  } finally {
    jsonQuerying.value = false
  }
}

async function drillIntoField(field: string) {
  const base = jsonQueryForm.value.path.trim() || '$'
  jsonQueryForm.value.path = base === '$' ? `$.${field}` : `${base}.${field}`
  await queryJson()
}

async function saveJsonEdit() {
  if (!store.selectedId) return
  const key = jsonQueryForm.value.key.trim()
  if (!key || !jsonEditorText.value.trim()) {
    ElMessage.warning('请填写键名与 JSON 内容')
    return
  }
  jsonSaving.value = true
  try {
    await multiModelApi.setJson({
      instanceId: store.selectedId,
      key,
      path: jsonQueryForm.value.path.trim() || '$',
      value: jsonEditorText.value
    })
    ElMessage.success('JSON 已保存')
    await Promise.all([refreshJsonKeys(), queryJson()])
  } finally {
    jsonSaving.value = false
  }
}

async function submitCreateJson() {
  if (!store.selectedId) return
  const f = jsonCreateForm.value
  if (!f.key.trim() || !f.value.trim()) {
    ElMessage.warning('请填写键名与 JSON 内容')
    return
  }
  jsonSaving.value = true
  try {
    await multiModelApi.setJson({
      instanceId: store.selectedId,
      key: f.key.trim(),
      path: f.path.trim() || '$',
      value: f.value
    })
    ElMessage.success(`JSON 键 ${f.key.trim()} 已写入`)
    jsonDialogVisible.value = false
    jsonQueryForm.value.key = f.key.trim()
    jsonQueryForm.value.path = '$'
    await Promise.all([refreshJsonKeys(), queryJson()])
  } finally {
    jsonSaving.value = false
  }
}

async function deleteJsonPath() {
  if (!store.selectedId) return
  const key = jsonQueryForm.value.key.trim()
  const path = jsonQueryForm.value.path.trim() || '$'
  if (!key) return
  await ElMessageBox.confirm(`确认删除 ${key} 路径 ${path}？`, '删除 JSON', { type: 'warning' })
  await multiModelApi.deleteJson(store.selectedId, key, path)
  ElMessage.success('已删除')
  await Promise.all([refreshJsonKeys(), queryJson()])
}

watch(
  () => store.selectedId,
  () => {
    vectorResult.value = null
    tsRange.value = null
    tsQueryForm.value.key = ''
    jsonResult.value = null
    jsonQueryForm.value.key = ''
    loadOverview()
  }
)

onMounted(loadOverview)
</script>

<template>
  <div class="page" v-loading="loadingSummary">
    <div class="page-header">
      <div>
        <h2 class="page-title">多模型工作台</h2>
        <p class="page-subtitle">
          直连 Tiering-KV 原生 Vector HNSW 检索、TimeSeries 时序降采样与 JSON Path 文档引擎（标准 Redis 自动降级提示）。
        </p>
      </div>
      <div class="header-actions">
        <el-button @click="loadOverview">
          <el-icon><Refresh /></el-icon> 刷新全部模型
        </el-button>
      </div>
    </div>

    <div class="kpi-grid">
      <StatCard
        label="向量索引总量"
        :value="vectorInfo?.supported === false ? '不支持' : (vectorInfo?.totalVectors ?? 0)"
        :tone="vectorInfo?.supported === false ? 'warn' : 'default'"
        :hint="vectorInfo?.supported ? `${vectorInfo.collections.length} 个 HNSW 集合` : 'VECTOR.* 命令族'"
      />
      <StatCard
        label="时序指标序列"
        :value="tsKeysInfo?.supported === false ? '不支持' : (tsKeysInfo?.series.length ?? 0)"
        :tone="tsKeysInfo?.supported === false ? 'warn' : 'default'"
        :hint="tsKeysInfo?.supported ? '支持 AVG/SUM/MIN/MAX/COUNT 降采样' : 'TS.* 命令族'"
      />
      <StatCard
        label="JSON 文档键"
        :value="jsonKeysInfo?.supported === false ? '不支持' : (jsonKeysInfo?.keys.length ?? 0)"
        :tone="jsonKeysInfo?.supported === false ? 'warn' : 'default'"
        :hint="jsonKeysInfo?.supported ? '支持 JSONPath 路径读写与数值自增' : 'JSON.* 命令族'"
      />
      <StatCard
        label="当前实例"
        :value="store.selected?.name ?? '未选择'"
        :tone="store.selected?.status === 'UP' ? 'success' : 'warn'"
        :hint="store.selected ? `${store.selected.host}:${store.selected.port}` : '请在顶栏选择实例'"
      />
    </div>

    <el-alert
      v-if="anyUnsupported"
      type="warning"
      show-icon
      :closable="false"
      class="unsupported-alert"
      :title="unsupportedMessage || '当前实例不支持 Tiering-KV 多模型命令'"
      description="请切换到运行 Tiering-KV 内核的实例以体验 Vector HNSW、TimeSeries 与 JSONPath 能力。"
    />

    <div class="panel">
      <el-tabs v-model="activeTab" class="mm-tabs">
        <!-- ==================== Vector HNSW ==================== -->
        <el-tab-pane label="Vector HNSW 检索" name="vector">
          <PageToolbar :meta="`共 ${vectorInfo?.collections.length ?? 0} 个集合 · ${vectorInfo?.totalVectors ?? 0} 条向量`">
            <el-select
              v-model="selectedCollection"
              placeholder="选择集合"
              filterable
              allow-create
              style="width: 180px"
            >
              <el-option
                v-for="col in vectorInfo?.collections ?? []"
                :key="col.name"
                :label="`${col.name} (${col.count})`"
                :value="col.name"
              />
            </el-select>
            <el-button v-if="auth.canOperate" type="primary" :disabled="vectorInfo?.supported === false" @click="openAddVector">
              <el-icon><Plus /></el-icon> 写入向量
            </el-button>
            <el-button
              v-if="auth.canOperate"
              :loading="vectorCheckpointing"
              :disabled="vectorInfo?.supported === false"
              @click="checkpointCollection"
            >
              <el-icon><Finished /></el-icon> 索引落盘
            </el-button>
          </PageToolbar>

          <div class="split-grid">
            <div class="sub-card">
              <h4 class="sub-title">集合清单</h4>
              <el-table :data="vectorInfo?.collections ?? []" size="small">
                <template #empty>
                  <EmptyState title="暂无向量集合" description="点击「写入向量」自动创建默认或自定义集合。" />
                </template>
                <el-table-column prop="name" label="集合名称">
                  <template #default="{ row }">
                    <el-button link type="primary" @click="selectedCollection = row.name">
                      <span class="mono">{{ row.name }}</span>
                    </el-button>
                  </template>
                </el-table-column>
                <el-table-column prop="count" label="向量数" width="88" align="right">
                  <template #default="{ row }"><span class="num">{{ row.count }}</span></template>
                </el-table-column>
                <el-table-column v-if="auth.canOperate" label="操作" width="76">
                  <template #default="{ row }">
                    <el-button link type="danger" size="small" @click="dropCollection(row.name)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>

            <div class="sub-card">
              <h4 class="sub-title">Top-K 相似度检索（VECTOR.SEARCH）</h4>
              <div class="query-bar">
                <el-input
                  v-model="vectorQueryText"
                  placeholder="输入查询向量，例如 0.1, 0.2, 0.3, 0.4"
                  class="flex-1"
                />
                <el-input-number v-model="vectorTopK" :min="1" :max="100" style="width: 116px" />
                <el-button
                  type="primary"
                  :loading="vectorSearching"
                  :disabled="vectorInfo?.supported === false"
                  @click="runVectorSearch"
                >
                  <el-icon><Search /></el-icon> 检索
                </el-button>
              </div>
              <div v-if="vectorResult" class="result-meta mono">
                集合 {{ vectorResult.collection }} · 命中 {{ vectorResult.hits.length }} 条 · 耗时 {{ vectorResult.costMs }} ms
              </div>
              <el-table :data="vectorResult?.hits ?? []" size="small" style="margin-top: 8px">
                <template #empty>
                  <EmptyState title="输入向量并点击检索" description="返回按相似度排序的 Top-K 向量 ID 与分值。" />
                </template>
                <el-table-column type="index" label="#" width="48" />
                <el-table-column prop="id" label="向量 ID" min-width="140">
                  <template #default="{ row }"><span class="mono">{{ row.id }}</span></template>
                </el-table-column>
                <el-table-column label="相似度 / 分值" min-width="200">
                  <template #default="{ row }">
                    <div class="score-cell">
                      <el-progress
                        :percentage="normalizeSimilarityPercent(row.score)"
                        :stroke-width="6"
                        :show-text="false"
                        style="width: 110px"
                      />
                      <span class="mono">{{ Number(row.score).toFixed(4) }}</span>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column v-if="auth.canOperate" label="操作" width="76">
                  <template #default="{ row }">
                    <el-button link type="danger" size="small" @click="deleteVectorItem(row.id)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </el-tab-pane>

        <!-- ==================== TimeSeries ==================== -->
        <el-tab-pane label="TimeSeries 时序分析" name="timeseries">
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

              <div v-if="auth.canOperate" class="inline-form">
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
        </el-tab-pane>

        <!-- ==================== JSON Path Explorer ==================== -->
        <el-tab-pane label="JSON 路径浏览器" name="json">
          <div class="split-grid">
            <div class="sub-card">
              <div class="query-bar">
                <el-input v-model="jsonPattern" placeholder="键过滤模式 (*)" size="small" class="flex-1" />
                <el-button size="small" @click="refreshJsonKeys">筛选</el-button>
                <el-button
                  v-if="auth.canOperate"
                  size="small"
                  type="primary"
                  :disabled="jsonKeysInfo?.supported === false"
                  @click="jsonDialogVisible = true"
                >
                  <el-icon><Plus /></el-icon> 新建
                </el-button>
              </div>
              <el-table :data="(jsonKeysInfo?.keys ?? []).map((k) => ({ key: k }))" size="small" style="margin-top: 8px">
                <template #empty>
                  <EmptyState title="暂无 JSON 键" description="点击「新建」写入第一份 JSON 文档。" />
                </template>
                <el-table-column prop="key" label="JSON 键名">
                  <template #default="{ row }">
                    <el-button link type="primary" @click="selectJsonKey(row.key)">
                      <span class="mono">{{ row.key }}</span>
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>

            <div class="sub-card">
              <h4 class="sub-title">JSONPath 实时查询与原地编辑（JSON.GET / JSON.SET）</h4>
              <div class="query-bar">
                <el-input v-model="jsonQueryForm.key" placeholder="JSON 键名" style="width: 190px" />
                <el-input v-model="jsonQueryForm.path" placeholder="JSONPath，例如 $ 或 $.tags" class="flex-1" />
                <el-button
                  type="primary"
                  :loading="jsonQuerying"
                  :disabled="jsonKeysInfo?.supported === false"
                  @click="queryJson"
                >
                  <el-icon><Search /></el-icon> 查询
                </el-button>
              </div>

              <div v-if="jsonResult" class="json-meta">
                <StatusBadge
                  :label="jsonResult.exists ? (jsonResult.valueType || 'json') : 'NOT FOUND'"
                  :tone="jsonResult.exists ? 'accent' : 'warn'"
                  mono
                />
                <div v-if="jsonResult.objectKeys.length > 0" class="field-chips">
                  <span class="muted">字段下钻：</span>
                  <el-button
                    v-for="field in jsonResult.objectKeys"
                    :key="field"
                    size="small"
                    text
                    bg
                    @click="drillIntoField(field)"
                  >
                    .{{ field }}
                  </el-button>
                </div>
              </div>

              <el-input
                v-model="jsonEditorText"
                type="textarea"
                :rows="12"
                placeholder="查询结果将格式化展示在此处，可直接编辑后保存"
                class="json-editor mono"
              />

              <div v-if="auth.canOperate" class="json-actions">
                <el-button
                  type="primary"
                  :loading="jsonSaving"
                  :disabled="jsonKeysInfo?.supported === false || !jsonQueryForm.key.trim()"
                  @click="saveJsonEdit"
                >
                  保存修改（JSON.SET）
                </el-button>
                <el-button
                  type="danger"
                  plain
                  :disabled="jsonKeysInfo?.supported === false || !jsonQueryForm.key.trim()"
                  @click="deleteJsonPath"
                >
                  <el-icon><Delete /></el-icon> 删除当前路径
                </el-button>
              </div>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- 写入向量对话框 -->
    <el-dialog v-model="addVectorVisible" title="写入向量（VECTOR.ADD）" width="480px">
      <el-form label-width="92px" @submit.prevent>
        <el-form-item label="集合名称">
          <el-input v-model="addVectorForm.collection" placeholder="default" />
        </el-form-item>
        <el-form-item label="向量 ID">
          <el-input v-model="addVectorForm.id" placeholder="例如 doc-001" />
        </el-form-item>
        <el-form-item label="向量分量">
          <el-input
            v-model="addVectorForm.valuesText"
            type="textarea"
            :rows="4"
            placeholder="逗号或空格分隔浮点数，如 0.1, 0.2, 0.3, 0.4"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addVectorVisible = false">取消</el-button>
        <el-button type="primary" :loading="addVectorSaving" @click="submitAddVector">写入</el-button>
      </template>
    </el-dialog>

    <!-- 新建 JSON 文档对话框 -->
    <el-dialog v-model="jsonDialogVisible" title="新建 JSON 文档（JSON.SET）" width="520px">
      <el-form label-width="84px" @submit.prevent>
        <el-form-item label="键名">
          <el-input v-model="jsonCreateForm.key" placeholder="例如 doc:profile:1" />
        </el-form-item>
        <el-form-item label="路径">
          <el-input v-model="jsonCreateForm.path" placeholder="$" />
        </el-form-item>
        <el-form-item label="JSON 值">
          <el-input v-model="jsonCreateForm.value" type="textarea" :rows="8" class="mono" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="jsonDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="jsonSaving" @click="submitCreateJson">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.header-actions {
  display: flex;
  gap: 8px;
}

.unsupported-alert {
  margin-bottom: 14px;
}

.mm-tabs {
  padding: 6px 16px 16px;
}

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

.flex-1 {
  flex: 1;
  min-width: 140px;
}

.result-meta {
  margin-top: 8px;
  font-size: var(--text-xs);
  color: var(--text-3);
}

.score-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.inline-form {
  border-top: 1px solid var(--line-soft);
  margin-top: 14px;
}

.json-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.field-chips {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
}

.muted {
  font-size: var(--text-xs);
  color: var(--text-3);
}

.json-editor {
  margin-top: 10px;
}

.json-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}
</style>
