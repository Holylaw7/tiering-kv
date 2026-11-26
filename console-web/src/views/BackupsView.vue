<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Calendar, Finished, FolderAdd, Refresh, VideoPlay } from '@element-plus/icons-vue'
import { backupApi } from '@/api'
import type {
  BackupRecord,
  BackupScheduleMode,
  BackupScheduleView,
  CheckpointStatusView
} from '@/api/types'
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
const schedules = ref<BackupScheduleView[]>([])
const loading = ref(false)
const creating = ref(false)
const checkpointing = ref(false)
const checkpointStatus = ref<CheckpointStatusView | null>(null)
const checkpointError = ref('')
const statusFilter = ref('')

// 定时备份策略对话框（ref 避免 Volar 3.3 深模板 reactive 收窄退化）
const scheduleDialogVisible = ref(false)
const scheduleSaving = ref(false)
const runningScheduleId = ref<number | null>(null)
const editingScheduleId = ref<number | null>(null)
const scheduleForm = ref<{
  instanceId: number | undefined
  name: string
  cronExpression: string
  mode: BackupScheduleMode
  retentionCount: number
  enabled: boolean
}>({
  instanceId: undefined,
  name: '',
  cronExpression: '0 2 * * *',
  mode: 'LOGICAL',
  retentionCount: 7,
  enabled: true
})

const CRON_PRESETS = [
  { label: '每天 02:00', value: '0 2 * * *' },
  { label: '每 6 小时', value: '0 */6 * * *' },
  { label: '每小时整点', value: '0 * * * *' },
  { label: '每周日 03:00', value: '0 3 * * SUN' }
] as const

const enabledScheduleCount = computed(() => schedules.value.filter((s) => s.enabled).length)

function openCreateSchedule() {
  editingScheduleId.value = null
  scheduleForm.value = {
    instanceId: store.selectedId ?? store.list[0]?.id,
    name: '',
    cronExpression: '0 2 * * *',
    mode: 'LOGICAL',
    retentionCount: 7,
    enabled: true
  }
  scheduleDialogVisible.value = true
}

function openEditSchedule(row: BackupScheduleView) {
  editingScheduleId.value = row.id
  scheduleForm.value = {
    instanceId: row.instanceId,
    name: row.name,
    cronExpression: row.cronExpression,
    mode: row.mode,
    retentionCount: row.retentionCount,
    enabled: row.enabled
  }
  scheduleDialogVisible.value = true
}

async function saveSchedule() {
  const f = scheduleForm.value
  if (!f.instanceId) {
    ElMessage.warning('请选择目标实例')
    return
  }
  if (!f.name.trim()) {
    ElMessage.warning('请填写策略名称')
    return
  }
  if (!f.cronExpression.trim()) {
    ElMessage.warning('请填写 Cron 表达式')
    return
  }
  scheduleSaving.value = true
  try {
    const payload = {
      instanceId: f.instanceId,
      name: f.name.trim(),
      cronExpression: f.cronExpression.trim(),
      mode: f.mode,
      retentionCount: f.retentionCount,
      enabled: f.enabled
    }
    if (editingScheduleId.value) {
      await backupApi.updateSchedule(editingScheduleId.value, payload)
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

    <div class="panel schedules-panel">
      <PageToolbar :meta="`定时备份策略 · 已启用 ${enabledScheduleCount} / ${schedules.length} 条（多副本 HA 租约单活调度）`">
        <el-button v-if="auth.canOperate" type="primary" plain @click="openCreateSchedule">
          <el-icon><Calendar /></el-icon> 新建定时策略
        </el-button>
      </PageToolbar>

      <el-table :data="schedules" size="small">
        <template #empty>
          <EmptyState
            title="暂无定时备份策略"
            description="点击「新建定时策略」配置 Cron 周期备份（支持逻辑导出与物理 BGSAVE 及留存份数自动清理）。"
          />
        </template>
        <el-table-column prop="name" label="策略名称" min-width="150" />
        <el-table-column label="目标实例" width="136">
          <template #default="{ row }">
            <span class="mono">{{ row.instanceName }} (#{{ row.instanceId }})</span>
          </template>
        </el-table-column>
        <el-table-column label="Cron 表达式" width="150">
          <template #default="{ row }">
            <span class="mono">{{ row.cronExpression }}</span>
          </template>
        </el-table-column>
        <el-table-column label="模式" width="116">
          <template #default="{ row }">
            <StatusBadge
              :label="row.mode === 'CHECKPOINT' ? '物理 BGSAVE' : '逻辑导出'"
              :tone="row.mode === 'CHECKPOINT' ? 'accent' : 'neutral'"
            />
          </template>
        </el-table-column>
        <el-table-column label="保留份数" width="88" align="right">
          <template #default="{ row }">
            <span class="num">{{ row.retentionCount }} 份</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <StatusBadge
              :label="row.enabled ? '已启用' : '已停用'"
              :tone="row.enabled ? 'success' : 'neutral'"
              dot
            />
          </template>
        </el-table-column>
        <el-table-column label="最近结果" min-width="160">
          <template #default="{ row }">
            <el-tooltip v-if="row.lastMessage" :content="row.lastMessage" placement="top">
              <StatusBadge
                :label="row.lastStatus || '未执行'"
                :tone="row.lastStatus === 'SUCCESS' ? 'success' : row.lastStatus === 'FAILED' ? 'danger' : 'warn'"
                dot
                mono
              />
            </el-tooltip>
            <span v-else class="muted">尚未执行</span>
          </template>
        </el-table-column>
        <el-table-column label="下次计划执行" width="170">
          <template #default="{ row }">
            <span class="mono">{{ row.nextRunAt || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230">
          <template #default="{ row }">
            <el-button
              v-if="auth.canOperate"
              link
              type="primary"
              size="small"
              :loading="runningScheduleId === row.id"
              @click="runScheduleNow(row as BackupScheduleView)"
            >
              <el-icon><VideoPlay /></el-icon> 立即执行
            </el-button>
            <el-button
              v-if="auth.canOperate"
              link
              type="primary"
              size="small"
              @click="openEditSchedule(row as BackupScheduleView)"
            >
              编辑
            </el-button>
            <el-button
              v-if="auth.canOperate"
              link
              size="small"
              @click="toggleSchedule(row as BackupScheduleView)"
            >
              {{ row.enabled ? '停用' : '启用' }}
            </el-button>
            <el-button
              v-if="auth.canOperate"
              link
              type="danger"
              size="small"
              @click="removeSchedule(row as BackupScheduleView)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
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
              <el-progress :percentage="restorePercent(row as BackupRecord)" :stroke-width="6" />
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
            <el-button v-if="auth.canOperate" link type="primary" size="small" :disabled="row.status !== 'SUCCESS'" @click="download(row as BackupRecord)">
              下载
            </el-button>
            <el-button
              v-if="auth.isAdmin"
              link
              type="warning"
              size="small"
              :disabled="row.status !== 'SUCCESS' || row.restoreStatus === 'RESTORING'"
              @click="restore(row as BackupRecord)"
            >
              恢复
            </el-button>
            <el-button
              v-if="auth.canOperate"
              link
              type="danger"
              size="small"
              :disabled="row.status === 'RUNNING' || row.restoreStatus === 'RESTORING'"
              @click="remove(row as BackupRecord)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog
      v-model="scheduleDialogVisible"
      :title="editingScheduleId ? '编辑定时备份策略' : '新建定时备份策略'"
      width="500px"
    >
      <el-form label-width="104px" @submit.prevent>
        <el-form-item label="策略名称">
          <el-input v-model="scheduleForm.name" placeholder="例如：每日凌晨逻辑备份" maxlength="128" />
        </el-form-item>
        <el-form-item label="目标实例">
          <el-select v-model="scheduleForm.instanceId" placeholder="选择实例" style="width: 100%">
            <el-option
              v-for="inst in store.list"
              :key="inst.id"
              :label="`${inst.name} (${inst.host}:${inst.port})`"
              :value="inst.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="Cron 表达式">
          <el-input v-model="scheduleForm.cronExpression" placeholder="支持 5 段 Unix 或 6 段 Spring Cron" />
          <div class="preset-row">
            <el-button
              v-for="preset in CRON_PRESETS"
              :key="preset.value"
              size="small"
              text
              bg
              @click="scheduleForm.cronExpression = preset.value"
            >
              {{ preset.label }}
            </el-button>
          </div>
        </el-form-item>
        <el-form-item label="备份模式">
          <el-radio-group v-model="scheduleForm.mode">
            <el-radio value="LOGICAL">逻辑导出（SCAN + GZIP）</el-radio>
            <el-radio value="CHECKPOINT">物理快照（BGSAVE）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="保留份数">
          <el-input-number v-model="scheduleForm.retentionCount" :min="1" :max="365" />
          <span class="muted" style="margin-left: 8px">超出后自动按安全认领协议清理旧备份</span>
        </el-form-item>
        <el-form-item label="启用策略">
          <el-switch v-model="scheduleForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="scheduleDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="scheduleSaving" @click="saveSchedule">保存</el-button>
      </template>
    </el-dialog>
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

.schedules-panel {
  margin-bottom: 14px;
}

.preset-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
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