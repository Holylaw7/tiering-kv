<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Finished, FolderAdd, Refresh } from '@element-plus/icons-vue'
import { backupApi } from '@/api'
import type {
  BackupRecord,
  BackupScheduleRequest,
  BackupScheduleView,
  CheckpointStatusView,
  RestorePreviewView
} from '@/api/types'
import { useInstanceStore } from '@/stores/instance'
import { useAuthStore } from '@/stores/auth'
import { formatBytes } from '@/utils/format'
import StatCard from '@/components/StatCard.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import BackupSchedulePanel from '@/components/backups/BackupSchedulePanel.vue'
import BackupRecordTable from '@/components/backups/BackupRecordTable.vue'
import RestorePreviewDialog from '@/components/backups/RestorePreviewDialog.vue'

const store = useInstanceStore()
const auth = useAuthStore()
const rows = ref<BackupRecord[]>([])
const schedules = ref<BackupScheduleView[]>([])
const loading = ref(false)
const creating = ref(false)
const checkpointing = ref(false)
const checkpointStatus = ref<CheckpointStatusView | null>(null)
const checkpointError = ref('')
const statusFilter = ref('')

const scheduleDialogVisible = ref(false)
const scheduleSaving = ref(false)
const runningScheduleId = ref<number | null>(null)

const verifyingId = ref<number | null>(null)
const restoreDialogVisible = ref(false)
const restorePreviewLoading = ref(false)
const restoreSubmitting = ref(false)
const restoreTarget = ref<BackupRecord | null>(null)
const restorePreview = ref<RestorePreviewView | null>(null)
const restoreCreateSafetySnapshot = ref(true)

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
      backupApi.schedules().then((list) => { schedules.value = list }),
      loadCheckpointStatus()
    ])
  } finally {
    loading.value = false
  }
}

let pollTimer: number | undefined
let restorePollTimer: number | undefined
let checkpointPollTimer: number | undefined

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

async function saveSchedule(editingId: number | null, payload: BackupScheduleRequest) {
  scheduleSaving.value = true
  try {
    if (editingId) {
      await backupApi.updateSchedule(editingId, payload)
      ElMessage.success('定时备份策略已更新')
    } else {
      await backupApi.createSchedule(payload)
      ElMessage.success('定时备份策略已创建')
    }
    scheduleDialogVisible.value = false
    await load()
  } finally {
    scheduleSaving.value = false
  }
}

async function toggleSchedule(row: BackupScheduleView) {
  await backupApi.updateSchedule(row.id, {
    instanceId: row.instanceId,
    name: row.name,
    cronExpression: row.cronExpression,
    mode: row.mode,
    retentionCount: row.retentionCount,
    enabled: !row.enabled
  })
  ElMessage.success(!row.enabled ? '策略已启用' : '策略已停用')
  await load()
}

async function removeSchedule(row: BackupScheduleView) {
  await ElMessageBox.confirm(`确认删除定时策略「${row.name}」？`, '删除策略', { type: 'warning' })
  await backupApi.removeSchedule(row.id)
  ElMessage.success('策略已删除')
  await load()
}

async function runScheduleNow(row: BackupScheduleView) {
  runningScheduleId.value = row.id
  try {
    const result = await backupApi.runScheduleNow(row.id)
    ElMessage.success(result.message || '策略已触发执行')
    await load()
    if (result.backupRecordId) {
      startPolling(result.backupRecordId)
    } else if (row.mode === 'CHECKPOINT' && checkpointStatus.value?.inProgress) {
      pollCheckpointCompletion()
    }
  } finally {
    runningScheduleId.value = null
  }
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

async function verifyBackup(row: BackupRecord) {
  verifyingId.value = row.id
  try {
    const result = await backupApi.verify(row.id)
    if (result.status === 'VERIFIED') {
      ElMessage.success(result.message || `校验通过（${result.validEntries} 条记录）`)
    } else {
      ElMessage.error(result.message || '归档校验异常，可能已损坏')
    }
    await load()
  } finally {
    verifyingId.value = null
  }
}

async function openRestoreWizard(row: BackupRecord) {
  restoreTarget.value = row
  restorePreview.value = null
  restoreCreateSafetySnapshot.value = false
  restoreDialogVisible.value = true
  restorePreviewLoading.value = true
  try {
    const preview = await backupApi.previewRestore(row.id)
    restorePreview.value = preview
    restoreCreateSafetySnapshot.value = preview.conflictKeys > 0
  } finally {
    restorePreviewLoading.value = false
  }
}

async function confirmRestore() {
  const target = restoreTarget.value
  if (!target) return
  restoreSubmitting.value = true
  try {
    const record = await backupApi.restore(target.id, {
      createSafetySnapshot: restoreCreateSafetySnapshot.value
    })
    restoreDialogVisible.value = false
    await load()
    if (record.restoreStatus === 'RESTORING') {
      const hint = record.preRestoreBackupId
        ? `已创建保护快照 #${record.preRestoreBackupId}，恢复任务在后台执行中`
        : '恢复任务已提交，页面会自动刷新进度'
      ElMessage.info(hint)
      startRestorePolling(record.id)
    } else if (record.restoreStatus === 'SUCCEEDED') {
      ElMessage.success(`已恢复 ${record.restoredKeys ?? 0} 个键`)
    } else if (record.restoreStatus === 'FAILED') {
      ElMessage.error(`恢复失败：${record.restoreMessage ?? '未知原因'}`)
    }
  } finally {
    restoreSubmitting.value = false
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

    <BackupSchedulePanel
      v-model:dialog-visible="scheduleDialogVisible"
      :schedules="schedules"
      :instances="store.list"
      :selected-instance-id="store.selectedId"
      :can-operate="auth.canOperate"
      :saving="scheduleSaving"
      :running-schedule-id="runningScheduleId"
      @save="saveSchedule"
      @toggle="toggleSchedule"
      @remove="removeSchedule"
      @run-now="runScheduleNow"
    />

    <BackupRecordTable
      v-model:status-filter="statusFilter"
      :rows="rows"
      :filtered-rows="filteredRows"
      :can-operate="auth.canOperate"
      :is-admin="auth.isAdmin"
      :verifying-id="verifyingId"
      @refresh="load"
      @verify="verifyBackup"
      @download="download"
      @restore="openRestoreWizard"
      @remove="remove"
    />

    <RestorePreviewDialog
      v-model="restoreDialogVisible"
      v-model:create-safety-snapshot="restoreCreateSafetySnapshot"
      :target="restoreTarget"
      :preview="restorePreview"
      :preview-loading="restorePreviewLoading"
      :submitting="restoreSubmitting"
      @confirm="confirmRestore"
    />
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
</style>
