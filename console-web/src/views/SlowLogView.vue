<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { slowLogApi } from '@/api'
import type { SlowLogEntryView, SlowLogView } from '@/api/types'
import { useInstanceStore } from '@/stores/instance'
import { useAuthStore } from '@/stores/auth'
import EmptyState from '@/components/EmptyState.vue'

const store = useInstanceStore()
const auth = useAuthStore()

const loading = ref(false)
const resetting = ref(false)
const limit = ref(50)
const commandFilter = ref('')
const sortBy = ref<'time' | 'duration'>('time')
const view = ref<SlowLogView | null>(null)

const filteredRows = computed<SlowLogEntryView[]>(() => {
  const entries = view.value?.entries ?? []
  const keyword = commandFilter.value.trim().toUpperCase()
  const filtered = keyword
    ? entries.filter((item) => item.command.toUpperCase().includes(keyword))
    : [...entries]
  if (sortBy.value === 'duration') {
    filtered.sort((a, b) => b.durationMicros - a.durationMicros || b.id - a.id)
  } else {
    filtered.sort((a, b) => b.id - a.id)
  }
  return filtered
})

function formatTimestamp(seconds: number): string {
  if (!seconds || seconds <= 0) return '—'
  return new Date(seconds * 1000).toLocaleString('zh-CN', { hour12: false })
}

function formatDuration(micros: number): string {
  if (micros >= 1_000_000) return `${(micros / 1_000_000).toFixed(2)} s`
  if (micros >= 1_000) return `${(micros / 1_000).toFixed(2)} ms`
  return `${micros} us`
}

async function load() {
  if (!store.selectedId) {
    view.value = null
    return
  }
  loading.value = true
  try {
    view.value = await slowLogApi.list(store.selectedId, limit.value)
  } finally {
    loading.value = false
  }
}

async function resetSlowLog() {
  if (!store.selectedId) return
  await ElMessageBox.confirm(
    `确认清空实例 #${store.selectedId} 的全部慢查询记录？此操作将写入审计日志。`,
    '清空慢日志',
    { type: 'warning' }
  )
  resetting.value = true
  try {
    await slowLogApi.reset(store.selectedId)
    ElMessage.success('已清空慢查询日志')
    await load()
  } finally {
    resetting.value = false
  }
}

watch(() => store.selectedId, load)
onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-header">
      <div>
        <h2 class="page-title">慢查询日志</h2>
        <p class="page-subtitle">实时读取数据面 SLOWLOG GET / LEN，敏感认证参数已自动脱敏</p>
      </div>
      <div class="header-actions">
        <el-select v-model="limit" style="width: 112px" @change="load">
          <el-option :value="20" label="最近 20 条" />
          <el-option :value="50" label="最近 50 条" />
          <el-option :value="100" label="最近 100 条" />
          <el-option :value="200" label="最近 200 条" />
        </el-select>
        <el-select v-model="sortBy" style="width: 140px">
          <el-option value="time" label="按时间倒序" />
          <el-option value="duration" label="按耗时降序" />
        </el-select>
        <el-input
          v-model="commandFilter"
          placeholder="过滤命令，如 SET"
          clearable
          style="width: 180px"
        />
        <el-button @click="load">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
        <el-button
          v-if="auth.canOperate"
          type="danger"
          plain
          :loading="resetting"
          :disabled="!store.selectedId || view?.supported === false"
          @click="resetSlowLog"
        >
          清空慢日志
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="view && !view.supported"
      :title="view.message || '当前实例不支持 SLOWLOG 命令'"
      type="warning"
      show-icon
      :closable="false"
      style="margin-bottom: 12px"
    />

    <div class="panel">
      <div class="summary-bar" v-if="store.selectedId && view?.supported">
        <span class="meta-label">实例 #{{ store.selectedId }} 环形缓冲总计：</span>
        <el-tag size="small" type="info">{{ view.totalLen }} 条</el-tag>
        <span class="meta-label">当前展示：{{ filteredRows.length }} 条</span>
      </div>

      <el-table :data="filteredRows" size="small">
        <template #empty>
          <EmptyState title="暂无慢查询记录" description="当命令执行耗时达到 slowlog-threshold-micros 时会在此实时展示。" />
        </template>
        <el-table-column prop="id" label="ID" width="84">
          <template #default="{ row }">
            <span class="mono">#{{ row.id }}</span>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="180">
          <template #default="{ row }">{{ formatTimestamp(row.timestampSeconds) }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="120">
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="row.durationMicros >= 50000 ? 'danger' : row.durationMicros >= 10000 ? 'warning' : 'success'"
            >
              {{ formatDuration(row.durationMicros) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="命令" width="140">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.command || '—' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="命令参数（已脱敏）" min-width="280">
          <template #default="{ row }">
            <span class="mono">{{ row.args.join(' ') }}</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.summary-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--tk-border);
}

.meta-label {
  font-size: 13px;
  color: var(--tk-text-2);
}
</style>
