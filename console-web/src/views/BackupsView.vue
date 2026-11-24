<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { backupApi } from '@/api'
import type { BackupRecord, CheckpointStatusView } from '@/api/types'
import { useInstanceStore } from '@/stores/instance'
import { useAuthStore } from '@/stores/auth'
import { formatBytes } from '@/utils/format'
import EmptyState from '@/components/EmptyState.vue'
import StatCard from '@/components/StatCard.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import PageToolbar from '@/components/PageToolbar.vue'

const store = useInstanceStore()
const auth = useAuthStore()
const rows = ref<BackupRecord[]>([])
const loading = ref(false)
const creating = ref(false)
const checkpointing = ref(false)
const checkpointStatus = ref<CheckpointStatusView | null>(null)
const checkpointError = ref('')
const statusFilter = ref('')

const filteredRows = computed(() => {
  if (!statusFilter.value) return rows.value
  return rows.value.filter((item) => item.status === statusFilter.value)
})
const successCount = computed(() => rows.value.filter((item) => item.status === 'SUCCESS').length)
const failedCount = computed(() => rows.value.filter((item) => item.status === 'FAILED').length)
const totalBackupBytes = computed(() =>
  rows.value.reduce((acc, item) => acc + (item.sizeBytes || 0), 0)
)

const lastSaveLabel = computed(() => {
  const status = checkpointStatus.value
  if (!status) return checkpointError.value || '未读取'
  if (!status.supported) return '当前实例不支持 BGSAVE/LASTSAVE'
  if (!status.lastSaveTime || status.lastSaveTime <= 0) return '尚未执行物理 Checkpoint'
  return new Date(status.lastSaveTime * 1000).toLocaleString('zh-CN', { hour12: false })
})

async function loadCheckpointStatus() {
  if (!store.selectedId) {
    checkpointStatus.value = null
    checkpointError.value = ''
    return
  }
  try {
    checkpointError.value = ''
    checkpointStatus.value = await backupApi.checkpointStatus(store.selectedId)
  } catch (err: unknown) {
    checkpointStatus.value = null
    checkpointError.value = err instanceof Error ? err.message : '物理快照状态读取失败'
  }
}

async function load() {
  loading.value = true
  try {
    await Promise.all([
      backupApi.list().then((list) => { rows.value = list }),
      loadCheckpointStatus()
    ])
  } finally {
    loading.value = false
  }
}

let pollTimer: number | undefined
let restorePollTimer: number | undefined
let checkpointPollTimer: number | undefined

function restorePercent(row: BackupRecord): number {
  const total = Math.max(1, row.keyCount || 0)
  const done = Math.max(0, row.restoredKeys || 0)
  if (row.restoreStatus === 'SUCCEEDED') return 100
  return Math.min(99, Math.round((done / total) * 100))
}

/** 轮询备份状态（TD-091 + O1-B）：实时刷新导出进度与终态。 */
function startPolling(recordId: number) {
  if (pollTimer) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
  let ticks = 0
  pollTimer = window.setInterval(async () => {
    ticks += 1
    await load()
    const current = rows.value.find((row) => row.id === recordId)
    if (!current || current.status !== 'RUNNING') {
      if (pollTimer) {
        window.clearInterval(pollTimer)
        pollTimer = undefined
      }
      if (current?.status === 'SUCCESS') {
        ElMessage.success(`备份完成，共 ${current.keyCount} 个键`)
      } else if (current?.status === 'FAILED') {
        ElMessage.error(`备份失败：${current.message ?? '未知原因'}`)
      }
    } else if (ticks >= 150) {
      if (pollTimer) {
        window.clearInterval(pollTimer)
        pollTimer = undefined
      }
      ElMessage.warning('备份仍在执行，请稍后刷新查看结果')
    }
  }, 2000)
}

/** 轮询异步恢复进度与终态（O1-B）：避免大库恢复击穿 HTTP 超时。 */
function startRestorePolling(recordId: number) {
  if (restorePollTimer) {
    window.clearInterval(restorePollTimer)
    restorePollTimer = undefined
  }
  let ticks = 0
  restorePollTimer = window.setInterval(async () => {
    ticks += 1
    await load()
    const current = rows.value.find((row) => row.id === recordId)
    if (!current || current.restoreStatus !== 'RESTORING') {
      if (restorePollTimer) {
        window.clearInterval(restorePollTimer)
        restorePollTimer = undefined
      }
      if (current?.restoreStatus === 'SUCCEEDED') {
        ElMessage.success(`已恢复 ${current.restoredKeys ?? 0} 个键`)
      } else if (current?.restoreStatus === 'FAILED') {
        ElMessage.error(`恢复失败：${current.restoreMessage ?? '未知原因'}`)
      }
    } else if (ticks >= 200) {
      if (restorePollTimer) {
        window.clearInterval(restorePollTimer)
        restorePollTimer = undefined
      }
      ElMessage.warning('恢复仍在后台执行，请稍后刷新查看结果')
    }
  }, 1500)
}

function stopPolling() {
  if (pollTimer) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
  if (restorePollTimer) {
    window.clearInterval(restorePollTimer)
    restorePollTimer = undefined
  }
  if (checkpointPollTimer) {
    window.clearInterval(checkpointPollTimer)
    checkpointPollTimer = undefined
  }
}

function pollCheckpointCompletion() {
  if (checkpointPollTimer) {
    window.clearInterval(checkpointPollTimer)
  }
  let ticks = 0
  checkpointPollTimer = window.setInterval(async () => {
    ticks += 1
    await loadCheckpointStatus()
    if (!checkpointStatus.value?.inProgress || ticks >= 15) {
      window.clearInterval(checkpointPollTimer)
      checkpointPollTimer = undefined
    }
  }, 1000)
}

async function triggerCheckpoint() {
  if (!store.selectedId) {
    ElMessage.warning('请先选择实例')
    return
  }
  checkpointing.value = true
  try {
    const result = await backupApi.checkpoint(store.selectedId)
    if (result.triggered) {
      ElMessage.success('物理 Checkpoint（BGSAVE）已触发')
    } else {
      ElMessage.warning(result.message || '后台物理快照正在执行中')
    }
    await loadCheckpointStatus()
    if (checkpointStatus.value?.inProgress) {
      pollCheckpointCompletion()
    }
  } finally {
    checkpointing.value = false
  }
}

async function create() {
  if (!store.selectedId) {
    ElMessage.warning('请先选择实例')
    return
  }
  creating.value = true
  try {
    const record = await backupApi.create(store.selectedId)
    await load()
    if (record.status === 'RUNNING') {
      ElMessage.info('备份任务已提交，页面会自动刷新结果')
      startPolling(record.id)
    } else {
      ElMessage.success(`备份完成，共 ${record.keyCount} 个键`)
    }
  } finally {
    creating.value = false
  }
}

async function restore(row: BackupRecord) {
  await ElMessageBox.confirm(
    `将用备份 ${row.fileName} 覆盖恢复实例数据，确认继续？`,
    '恢复数据',
    { type: 'warning' }
  )
  const record = await backupApi.restore(row.id)
  await load()
  if (record.restoreStatus === 'RESTORING') {
    ElMessage.info('恢复任务已提交，页面会自动刷新进度')
    startRestorePolling(record.id)
  } else if (record.restoreStatus === 'SUCCEEDED') {
    ElMessage.success(`已恢复 ${record.restoredKeys ?? 0} 个键`)
  } else if (record.restoreStatus === 'FAILED') {
    ElMessage.error(`恢复失败：${record.restoreMessage ?? '未知原因'}`)
  }
}

async function remove(row: BackupRecord) {
  await ElMessageBox.confirm('确认删除该备份文件及记录？', '提示', { type: 'warning' })
  await backupApi.remove(row.id)
  ElMessage.success('已删除')
  await load()
}

function download(row: BackupRecord) {
  const token = localStorage.getItem('tk_console_token')
  const url = `/api/v1/backups/${row.id}/download`
  fetch(url, { headers: token ? { Authorization: 'Bearer ' + token } : {} })
    .then(async (resp) => {
      if (!resp.ok) {
        throw new Error(`HTTP ${resp.status}`)
      }
      return resp.blob()
    })
    .then((blob) => {
      const link = document.createElement('a')
      link.href = URL.createObjectURL(blob)
      link.download = row.fileName
      link.click()
      URL.revokeObjectURL(link.href)
    })
    .catch(() => ElMessage.error('下载失败'))
}

watch(() => store.selectedId, loadCheckpointStatus)
onMounted(async () => {
  await load()
  const running = rows.value.find((row) => row.status === 'RUNNING')
  if (running) startPolling(running.id)
  const restoring = rows.value.find((row) => row.restoreStatus === 'RESTORING')
  if (restoring) startRestorePolling(restoring.id)
})
onUnmounted(stopPolling)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-header">
      <div>
        <h2 class="page-title">备份与恢复</h2>
        <p class="page-subtitle">逻辑备份经 SCAN 流式压缩导出（支持本地或 S3 对象存储，同实例并发返回 409），配合内核 BGSAVE 物理快照。</p>
      </div>
      <div class="header-actions">
        <el-button
          v-if="auth.canOperate"
          :loading="checkpointing"
          :disabled="checkpointStatus?.supported === false || checkpointStatus?.inProgress"
          @click="triggerCheckpoint"
        >
          <el-icon><Finished /></el-icon> 触发物理 Checkpoint
        </el-button>
        <el-button v-if="auth.canOperate" type="primary" :loading="creating" @click="create">
          <el-icon><FolderAdd /></el-icon> 立即备份
        </el-button>
      </div>
    </div>

    <div class="kpi-grid">
      <StatCard label="备份记录" :value="rows.length" :hint="`成功 ${successCount} 份`" />
      <StatCard
        label="异常 / 运行"
        :value="failedCount"
        :tone="failedCount > 0 ? 'danger' : 'default'"
        :hint="rows.some((r) => r.status === 'RUNNING') ? '存在执行中导出任务' : '当前无失败阻塞'"
      />
      <StatCard label="累计备份体积" :value="formatBytes(totalBackupBytes)" hint="Gzip / JSONL 导出文件" />
      <StatCard
        label="物理快照状态"
        :value="
          checkpointStatus?.inProgress
            ? '落盘中'
            : checkpointStatus?.supported === false
              ? '不支持'
              : checkpointStatus?.lastStatus === 'err'
                ? '失败'
                : '就绪'
        "
        :tone="checkpointStatus?.inProgress ? 'warn' : checkpointStatus?.lastStatus === 'err' ? 'danger' : 'success'"
        :hint="lastSaveLabel"
      />
    </div>

    <div class="panel checkpoint-strip" v-if="store.selectedId">
      <div class="checkpoint-meta">
        <StatusBadge label="BGSAVE / LASTSAVE" tone="accent" mono />
        <span class="meta-label">实例 #{{ store.selectedId }} 最近物理 Checkpoint：</span>
        <span class="mono meta-value">{{ lastSaveLabel }}</span>
        <StatusBadge
          v-if="checkpointStatus?.supported"
          :label="checkpointStatus.inProgress ? '落盘中' : checkpointStatus.lastStatus === 'err' ? '上次失败' : '就绪'"
          :tone="checkpointStatus.inProgress ? 'warn' : checkpointStatus.lastStatus === 'err' ? 'danger' : 'success'"
          dot
        />
        <span v-if="checkpointStatus?.lastDurationMillis && checkpointStatus.lastSaveTime > 0" class="muted mono">
          耗时 {{ checkpointStatus.lastDurationMillis }} ms
        </span>
      </div>
      <el-button size="small" @click="loadCheckpointStatus">
        <el-icon><Refresh /></el-icon> 刷新快照状态
      </el-button>
    </div>

    <div class="panel">
      <PageToolbar :meta="`展示 ${filteredRows.length} / ${rows.length} 条 · 恢复采用合并式幂等回放`">
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px">
          <el-option value="SUCCESS" label="SUCCESS（成功）" />
          <el-option value="RUNNING" label="RUNNING（进行中）" />
          <el-option value="FAILED" label="FAILED（失败）" />
        </el-select>
        <el-button @click="load">
          <el-icon><Refresh /></el-icon> 刷新列表
        </el-button>
      </PageToolbar>

      <el-table :data="filteredRows" size="small">
        <template #empty>
          <EmptyState title="还没有备份" description="点击右上角「立即备份」为该实例创建第一份快照。" />
        </template>
        <el-table-column prop="fileName" label="文件名" min-width="240">
          <template #default="{ row }">
            <span class="mono">{{ row.fileName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="格式" width="90">
          <template #default="{ row }">
            <StatusBadge
              :label="row.fileName.endsWith('.gz') ? 'GZIP' : 'JSONL'"
              :tone="row.fileName.endsWith('.gz') ? 'accent' : 'neutral'"
              mono
            />
          </template>
        </el-table-column>
        <el-table-column label="实例" width="84">
          <template #default="{ row }"><span class="mono">#{{ row.instanceId }}</span></template>
        </el-table-column>
        <el-table-column label="键数" width="96" align="right">
          <template #default="{ row }"><span class="num">{{ row.keyCount }}</span></template>
        </el-table-column>
        <el-table-column label="大小" width="108" align="right">
          <template #default="{ row }"><span class="num">{{ formatBytes(row.sizeBytes) }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="140">
          <template #default="{ row }">
            <StatusBadge
              :label="row.status"
              :tone="row.status === 'SUCCESS' ? 'success' : row.status === 'FAILED' ? 'danger' : 'warn'"
              dot
              mono
            />
            <div v-if="row.status === 'RUNNING'" class="progress-hint mono">
              已导出 {{ row.processedKeys ?? 0 }} 键
            </div>
          </template>
        </el-table-column>
        <el-table-column label="恢复进度" width="168">
          <template #default="{ row }">
            <div v-if="row.restoreStatus === 'RESTORING'" class="restore-progress">
              <el-progress :percentage="restorePercent(row)" :stroke-width="6" />
              <span class="progress-hint mono">{{ row.restoredKeys ?? 0 }} / {{ row.keyCount }}</span>
            </div>
            <StatusBadge
              v-else-if="row.restoreStatus === 'SUCCEEDED'"
              :label="`已恢复 ${row.restoredKeys ?? 0} 键`"
              tone="success"
              dot
            />
            <el-tooltip
              v-else-if="row.restoreStatus === 'FAILED'"
              :content="row.restoreMessage || '恢复中断'"
              placement="top"
            >
              <StatusBadge
                :label="`失败 (${row.restoredKeys ?? 0}/${row.keyCount})`"
                tone="danger"
                dot
              />
            </el-tooltip>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180" />
        <el-table-column label="操作" width="210">
          <template #default="{ row }">
            <el-button v-if="auth.canOperate" link type="primary" size="small" :disabled="row.status !== 'SUCCESS'" @click="download(row)">
              下载
            </el-button>
            <el-button
              v-if="auth.isAdmin"
              link
              type="warning"
              size="small"
              :disabled="row.status !== 'SUCCESS' || row.restoreStatus === 'RESTORING'"
              @click="restore(row)"
            >
              恢复
            </el-button>
            <el-button
              v-if="auth.canOperate"
              link
              type="danger"
              size="small"
              :disabled="row.status === 'RUNNING' || row.restoreStatus === 'RESTORING'"
              @click="remove(row)"
            >
              删除
            </el-button>
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

.checkpoint-strip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 12px 16px;
  margin-bottom: 14px;
}

.checkpoint-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.meta-label {
  font-size: var(--text-sm);
  color: var(--text-2);
}

.meta-value {
  font-size: var(--text-sm);
  color: var(--text-1);
}

.muted {
  font-size: var(--text-xs);
  color: var(--text-3);
}

.progress-hint {
  margin-top: 2px;
  font-size: 11px;
  color: var(--text-3);
}

.restore-progress {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
</style>