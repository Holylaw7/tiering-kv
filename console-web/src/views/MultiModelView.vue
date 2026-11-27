<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { multiModelApi } from '@/api'
import type { JsonKeysView, TimeSeriesKeysView, VectorCollectionsView } from '@/api/types'
import { useInstanceStore } from '@/stores/instance'
import { useAuthStore } from '@/stores/auth'
import StatCard from '@/components/StatCard.vue'
import VectorTab from '@/components/multimodel/VectorTab.vue'
import TimeSeriesTab from '@/components/multimodel/TimeSeriesTab.vue'
import JsonTab from '@/components/multimodel/JsonTab.vue'

const store = useInstanceStore()
const auth = useAuthStore()

const activeTab = ref<'vector' | 'timeseries' | 'json'>('vector')
const loadingSummary = ref(false)
const vectorInfo = ref<VectorCollectionsView | null>(null)
const tsKeysInfo = ref<TimeSeriesKeysView | null>(null)
const jsonKeysInfo = ref<JsonKeysView | null>(null)
const jsonPattern = ref('*')

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
  } finally {
    loadingSummary.value = false
  }
}

async function refreshJsonKeys() {
  if (!store.selectedId) return
  jsonKeysInfo.value = await multiModelApi.jsonKeys(store.selectedId, jsonPattern.value, 100)
}

watch(() => store.selectedId, loadOverview)
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
        <el-tab-pane label="Vector HNSW 检索" name="vector">
          <VectorTab
            :instance-id="store.selectedId"
            :vector-info="vectorInfo"
            :can-operate="auth.canOperate"
            @refresh="loadOverview"
          />
        </el-tab-pane>

        <el-tab-pane label="TimeSeries 时序分析" name="timeseries">
          <TimeSeriesTab
            :instance-id="store.selectedId"
            :ts-keys-info="tsKeysInfo"
            :can-operate="auth.canOperate"
            @refresh="loadOverview"
          />
        </el-tab-pane>

        <el-tab-pane label="JSON 路径浏览器" name="json">
          <JsonTab
            v-model:pattern="jsonPattern"
            :instance-id="store.selectedId"
            :json-keys-info="jsonKeysInfo"
            :can-operate="auth.canOperate"
            @refresh-keys="refreshJsonKeys"
          />
        </el-tab-pane>
      </el-tabs>
    </div>
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
</style>
