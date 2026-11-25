<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { systemApi } from '@/api'
import type { SystemInfo } from '@/api/types'
import { useInstanceStore } from '@/stores/instance'
import { formatBytes } from '@/utils/format'
import {
  buildInfoSections,
  countSectionMatches,
  filterSectionEntries,
  formatSectionJson,
  formatSectionKvText
} from '@/utils/infoSections'

const store = useInstanceStore()
const info = ref<SystemInfo>()
const loading = ref(false)
const configFilter = ref('')
const sectionFilter = ref('')
const extendedOnly = ref(false)
const activeSectionName = ref('')

const allSections = computed(() => buildInfoSections(info.value?.sections))
const hasExtendedSections = computed(() => allSections.value.some((sec) => sec.extended))

const visibleSections = computed(() => {
  if (extendedOnly.value && hasExtendedSections.value) {
    return allSections.value.filter((sec) => sec.extended)
  }
  return allSections.value
})

const activeSection = computed(
  () =>
    visibleSections.value.find((sec) => sec.name === activeSectionName.value) ??
    visibleSections.value[0]
)

const activeSectionEntries = computed(() =>
  filterSectionEntries(activeSection.value, sectionFilter.value)
)

const configEntries = computed(() => {
  const entries = Object.entries(info.value?.config ?? {}).map(([key, value]) => ({ key, value }))
  const q = configFilter.value.trim().toLowerCase()
  if (!q) return entries
  return entries.filter((item) => item.key.toLowerCase().includes(q) || item.value.toLowerCase().includes(q))
})

watch(
  visibleSections,
  (list) => {
    if (!list.length) {
      activeSectionName.value = ''
      return
    }
    if (!list.some((sec) => sec.name === activeSectionName.value)) {
      activeSectionName.value = list[0].name
    }
  },
  { immediate: true }
)

async function copyText(text: string, label: string) {
  try {
    if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
      await navigator.clipboard.writeText(text)
    } else {
      const textarea = document.createElement('textarea')
      textarea.value = text
      textarea.style.position = 'fixed'
      textarea.style.opacity = '0'
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
    }
    ElMessage.success(`已复制 ${label}`)
  } catch {
    ElMessage.error('复制失败，请检查浏览器剪贴板权限')
  }
}

function copyActiveKv() {
  if (!activeSection.value) return
  copyText(
    formatSectionKvText(activeSection.value, activeSectionEntries.value),
    `# ${activeSection.value.name} 文本`
  )
}

function copyActiveJson() {
  if (!activeSection.value) return
  copyText(
    formatSectionJson(activeSection.value, activeSectionEntries.value),
    `${activeSection.value.name} JSON`
  )
}

async function load() {
  if (!store.selectedId) return
  loading.value = true
  try {
    info.value = await systemApi.info(store.selectedId)
  } finally {
    loading.value = false
  }
}

function uptime(seconds: number): string {
  if (!seconds) return '-'
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  return days > 0 ? `${days} 天 ${hours} 小时` : hours > 0 ? `${hours} 小时 ${minutes} 分` : `${minutes} 分`
}

function usage(used: number, max: number): number {
  if (!max) return 0
  return Math.min(100, Math.round((used / max) * 100))
}

watch(() => store.selectedId, load)
onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-header">
      <div>
        <h2 class="page-title">系统信息</h2>
        <p class="page-subtitle">
          直接读取内核 <span class="mono">INFO</span> 与 <span class="mono">CONFIG GET</span>（只读，不落库）
        </p>
      </div>
      <el-button @click="load">刷新</el-button>
    </div>

    <el-alert
      v-if="info?.warnings?.length"
      type="warning"
      :closable="false"
      show-icon
      class="warn"
      :title="'部分信息不可用：' + info.warnings.join('；')"
    />

    <div class="cards">
      <div class="panel card">
        <div class="card-title">服务端</div>
        <el-descriptions :column="1" size="small" class="kv">
          <el-descriptions-item label="版本">{{ info?.server.version || '-' }}</el-descriptions-item>
          <el-descriptions-item label="运行模式">{{ info?.server.mode || '-' }}</el-descriptions-item>
          <el-descriptions-item label="运行时长">{{ uptime(info?.server.uptimeSeconds ?? 0) }}</el-descriptions-item>
          <el-descriptions-item label="监听端口">{{ info?.server.tcpPort || '-' }}</el-descriptions-item>
          <el-descriptions-item label="JDK">{{ info?.server.jdk || '-' }}</el-descriptions-item>
          <el-descriptions-item label="系统 / 架构">
            {{ info?.server.os || '-' }} / {{ info?.server.arch || '-' }} 位
          </el-descriptions-item>
          <el-descriptions-item label="进程 ID">{{ info?.server.processId || '-' }}</el-descriptions-item>
        </el-descriptions>
      </div>

      <div class="panel card">
        <div class="card-title">连接</div>
        <el-descriptions :column="1" size="small" class="kv">
          <el-descriptions-item label="当前连接">{{ info?.clients.connected ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="处理中请求">{{ info?.clients.activeRequests ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="累计连接">{{ info?.clients.totalConnections ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="错误计数">{{ info?.clients.errors ?? 0 }}</el-descriptions-item>
        </el-descriptions>
      </div>

      <div class="panel card">
        <div class="card-title">内存</div>
        <el-descriptions :column="1" size="small" class="kv">
          <el-descriptions-item label="引擎已用">{{ formatBytes(info?.memory.usedBytes) }}</el-descriptions-item>
          <el-descriptions-item label="引擎上限">{{ formatBytes(info?.memory.maxBytes) }}</el-descriptions-item>
          <el-descriptions-item label="JVM 已用">{{ formatBytes(info?.memory.jvmUsedBytes) }}</el-descriptions-item>
          <el-descriptions-item label="JVM 堆上限">{{ formatBytes(info?.memory.maxJvmHeapBytes) }}</el-descriptions-item>
          <el-descriptions-item label="引擎使用率">
            <el-progress
              :percentage="usage(info?.memory.usedBytes ?? 0, info?.memory.maxBytes ?? 0)"
              :stroke-width="10"
              :status="usage(info?.memory.usedBytes ?? 0, info?.memory.maxBytes ?? 0) > 85 ? 'exception' : undefined"
            />
          </el-descriptions-item>
        </el-descriptions>
      </div>

      <div class="panel card">
        <div class="card-title">吞吐</div>
        <el-descriptions :column="1" size="small" class="kv">
          <el-descriptions-item label="QPS">{{ (info?.stats.qps ?? 0).toFixed(2) }}</el-descriptions-item>
          <el-descriptions-item label="瞬时 OPS">
            {{ (info?.stats.instantaneousOpsPerSec ?? 0).toFixed(2) }}
          </el-descriptions-item>
          <el-descriptions-item label="平均延迟">{{ (info?.stats.avgLatencyMs ?? 0).toFixed(3) }} ms</el-descriptions-item>
          <el-descriptions-item label="键总数">{{ info?.stats.totalKeys ?? 0 }}</el-descriptions-item>
        </el-descriptions>
      </div>
    </div>

    <div class="panel">
      <div class="card-title">键空间</div>
      <el-table :data="info?.keyspace ?? []" size="small" empty-text="暂无数据">
        <el-table-column prop="db" label="库" width="120" />
        <el-table-column prop="keys" label="键数" width="140" />
        <el-table-column prop="expires" label="带过期" width="140" />
        <el-table-column prop="avgTtl" label="平均 TTL (ms)" />
      </el-table>
    </div>

    <div class="panel" data-testid="info-sections-panel">
      <div class="config-head">
        <div class="section-heading">
          <div class="card-title" style="margin-bottom: 0">内核扩展段落（INFO Sections）</div>
          <span v-if="allSections.length" class="section-meta">
            共 {{ visibleSections.length }} 段
          </span>
        </div>
        <div class="section-tools">
          <el-checkbox v-if="hasExtendedSections" v-model="extendedOnly" size="small">
            仅扩展段落
          </el-checkbox>
          <el-input
            v-model="sectionFilter"
            placeholder="过滤指标键或值，如 memtable / rdb"
            clearable
            size="small"
            style="width: 240px"
          />
          <el-button
            size="small"
            :disabled="!activeSection || activeSectionEntries.length === 0"
            @click="copyActiveKv"
          >
            复制文本
          </el-button>
          <el-button
            size="small"
            :disabled="!activeSection || activeSectionEntries.length === 0"
            @click="copyActiveJson"
          >
            复制 JSON
          </el-button>
        </div>
      </div>

      <div v-if="visibleSections.length" class="section-tabs" role="tablist" aria-label="INFO 段落">
        <button
          v-for="sec in visibleSections"
          :key="sec.name"
          type="button"
          role="tab"
          class="section-tab"
          :class="{
            active: activeSection?.name === sec.name,
            extended: sec.extended,
            dimmed: sectionFilter.trim().length > 0 && countSectionMatches(sec, sectionFilter) === 0
          }"
          :aria-selected="activeSection?.name === sec.name"
          :data-section="sec.name"
          @click="activeSectionName = sec.name"
        >
          <span class="tab-name">{{ sec.name }}</span>
          <span v-if="sec.label !== sec.name" class="tab-label">{{ sec.label }}</span>
          <span class="tab-count">{{ countSectionMatches(sec, sectionFilter) }}</span>
        </button>
      </div>

      <el-table
        v-if="visibleSections.length"
        :data="activeSectionEntries"
        size="small"
        max-height="360"
        :empty-text="sectionFilter.trim() ? '当前段落无匹配指标' : '当前段落暂无指标'"
      >
        <el-table-column prop="key" label="指标键" min-width="240">
          <template #default="{ row }"><span class="mono">{{ row.key }}</span></template>
        </el-table-column>
        <el-table-column prop="value" label="值" min-width="200">
          <template #default="{ row }"><span class="mono">{{ row.value }}</span></template>
        </el-table-column>
      </el-table>
      <div v-else class="empty-sections">内核未返回 INFO 段落</div>
    </div>

    <div class="panel">
      <div class="config-head">
        <div class="card-title" style="margin-bottom: 0">配置（只读）</div>
        <el-input
          v-model="configFilter"
          placeholder="过滤配置项，如 slowlog"
          clearable
          style="width: 220px"
        />
      </div>
      <el-table
        :data="configEntries"
        size="small"
        empty-text="内核未返回配置"
      >
        <el-table-column prop="key" label="配置项" width="220" />
        <el-table-column prop="value" label="值">
          <template #default="{ row }"><span class="mono">{{ row.value }}</span></template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.kv :deep(.el-descriptions__cell) {
  padding: 7px 0 !important;
}

.kv :deep(.el-descriptions__label) {
  width: 96px;
  font-size: var(--text-sm);
  color: var(--text-2) !important;
}

.kv :deep(.el-descriptions__content) {
  font-size: var(--text-sm);
  color: var(--text-1);
}

.kv :deep(.el-descriptions__table) {
  border-collapse: collapse;
}

.kv :deep(.el-descriptions__body) {
  background: transparent;
}

.kv :deep(tr) {
  border-bottom: 1px solid var(--line-soft);
}

.kv :deep(tr:last-child) {
  border-bottom: none;
}
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(248px, 1fr));
  gap: 14px;
  margin-bottom: 14px;
}

.card-title {
  font-size: var(--text-base);
  font-weight: 600;
  color: var(--text);
  margin-bottom: 10px;
}

.config-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.panel + .panel {
  margin-top: 14px;
}

.section-heading {
  display: flex;
  align-items: center;
  gap: 8px;
}

.section-meta {
  font-size: var(--text-xs);
  color: var(--text-2);
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--surface-2, rgba(148, 163, 184, 0.14));
}

.section-tools {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.section-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.section-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  border-radius: 8px;
  border: 1px solid var(--line-soft);
  background: transparent;
  color: var(--text-1);
  font-size: var(--text-xs);
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease, opacity 0.15s ease;
}

.section-tab:hover {
  border-color: var(--el-color-primary);
}

.section-tab.active {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 600;
}

.section-tab.dimmed {
  opacity: 0.45;
}

.tab-label {
  color: var(--text-2);
  font-weight: 400;
}

.section-tab.active .tab-label {
  color: var(--el-color-primary);
}

.tab-count {
  padding: 0 6px;
  border-radius: 999px;
  font-variant-numeric: tabular-nums;
  background: rgba(148, 163, 184, 0.18);
  color: var(--text-2);
}

.section-tab.active .tab-count {
  background: var(--el-color-primary);
  color: #fff;
}

.empty-sections {
  padding: 24px 0;
  text-align: center;
  color: var(--text-2);
  font-size: var(--text-sm);
}

.warn {
  margin-bottom: 14px;
}
</style>