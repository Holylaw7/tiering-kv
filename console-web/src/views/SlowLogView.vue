<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { slowLogApi } from '@/api'
import type { SlowLogEntryView, SlowLogView } from '@/api/types'
import { useInstanceStore } from '@/stores/instance'
import { useAuthStore } from '@/stores/auth'
import EmptyState from '@/components/EmptyState.vue'
import StatCard from '@/components/StatCard.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import PageToolbar from '@/components/PageToolbar.vue'

const router = useRouter()
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

const maxDurationMicros = computed(() =>
  filteredRows.value.reduce((max, item) => Math.max(max, item.durationMicros), 0)
)
const avgDurationMicros = computed(() => {
  if (!filteredRows.value.length) return 0
  const sum = filteredRows.value.reduce((acc, item) => acc + item.durationMicros, 0)
  return Math.round(sum / filteredRows.value.length)
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

function durationTone(micros: number): 'danger' | 'warn' | 'success' {
  if (micros >= 50_000) return 'danger'
  if (micros >= 10_000) return 'warn'
  return 'success'
}

function openInTerminal(row: SlowLogEntryView) {
  const full = [row.command, ...row.args].filter(Boolean).join(' ')
  router.push({ path: '/terminal', query: { cmd: full } })
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
      style="margin-bottom: 14px"
    />

    <div class="kpi-grid">
      <StatCard label="环形缓冲总计" :value="view?.totalLen ?? 0" hint="内核 SLOWLOG LEN" />
      <StatCard label="当前检索展示" :value="filteredRows.length" :hint="`拉取上限 ${limit} 条`" />
      <StatCard
        label="当前最慢耗时"
        :value="filteredRows.length ? formatDuration(maxDurationMicros) : '—'"
        :tone="maxDurationMicros >= 50000 ? 'danger' : maxDurationMicros >= 10000 ? 'warn' : 'default'"
        hint="≥ 50 ms 标记高延迟"
      />
      <StatCard
        label="筛选样本均耗"
        :value="filteredRows.length ? formatDuration(avgDurationMicros) : '—'"
        hint="仅统计已展示条目"
      />
    </div>

    <div class="panel">
      <PageToolbar :meta="`实例 #${store.selectedId ?? '-'} · 按 ${sortBy === 'duration' ? '耗时降序' : '时间倒序'}`">
        <el-select v-model="limit" style="width: 118px" @change="load">
          <el-option :value="20" label="最近 20 条" />
          <el-option :value="50" label="最近 50 条" />
          <el-option :value="100" label="最近 100 条" />
          <el-option :value="200" label="最近 200 条" />
        </el-select>
        <el-select v-model="sortBy" style="width: 134px">
          <el-option value="time" label="按时间倒序" />
          <el-option value="duration" label="按耗时降序" />
        </el-select>
        <el-input
          v-model="commandFilter"
          placeholder="过滤命令，如 SET"
          clearable
          style="width: 180px"
        />
      </PageToolbar>

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
        <el-table-column label="耗时" width="128">
          <template #default="{ row }">
            <StatusBadge
              :label="formatDuration(row.durationMicros)"
              :tone="durationTone(row.durationMicros)"
              dot
              mono
            />
          </template>
        </el-table-column>
        <el-table-column label="命令" width="140">
          <template #default="{ row }">
            <StatusBadge :label="row.command || '—'" tone="accent" mono />
          </template>
        </el-table-column>
        <el-table-column label="命令参数（已脱敏）" min-width="260">
          <template #default="{ row }">
            <span class="mono">{{ row.args.join(' ') }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openInTerminal(row)">带入终端</el-button>
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
</style>
