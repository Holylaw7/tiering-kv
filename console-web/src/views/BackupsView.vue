<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { backupApi } from '@/api'
import type { BackupRecord, CheckpointStatusView } from '@/api/types'
import { useInstanceStore } from '@/stores/instance'
import { useAuthStore } from '@/stores/auth'
import { formatBytes } from '@/utils/format'
import EmptyState from '@/components/EmptyState.vue'

const store = useInstanceStore()
const auth = useAuthStore()
const rows = ref<BackupRecord[]>([])
const loading = ref(false)
const creating = ref(false)
const checkpointing = ref(false)
const checkpointStatus = ref<CheckpointStatusView | null>(null)
const checkpointError = ref('')

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
let checkpointPollTimer: number | undefined

/** 轮询备份状态（TD-091）：异步任务提交后最多观察 60 秒。 */
function startPolling(recordId: number) {
  stopPolling()
  let ticks = 0
  pollTimer = window.setInterval(async () => {
    ticks += 1
    await load()
    const current = rows.value.find((row) => row.id === recordId)
    if (!current || current.status !== 'RUNNING') {
      stopPolling()
      if (current?.status === 'SUCCESS') {
        ElMessage.success(`备份完成，共 ${current.keyCount} 个键`)
      } else if (current?.status === 'FAILED') {
        ElMessage.error(`备份失败：${current.message ?? '未知原因'}`)
      }
    } else if (ticks >= 30) {
      stopPolling()
      ElMessage.warning('备份仍在执行，请稍后刷新查看结果')
    }
  }, 2000)
}

function stopPolling() {
  if (pollTimer) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
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
  const count = await backupApi.restore(row.id)
  ElMessage.success(`已恢复 ${count} 个键`)
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
onMounted(load)
onUnmounted(stopPolling)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-header">
      <div>
        <h2 class="page-title">备份与恢复</h2>
        <p class="page-subtitle">逻辑备份：SCAN 全量导出为 JSONL，后台异步执行（同实例并发提交返回 409），按类型回放恢复</p>
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

    <div class="panel checkpoint-strip" v-if="store.selectedId">
      <div class="checkpoint-meta">
        <span class="meta-label">实例 #{{ store.selectedId }} 物理快照（BGSAVE / LASTSAVE）：</span>
        <span class="mono">{{ lastSaveLabel }}</span>
        <el-tag
          v-if="checkpointStatus?.supported"
          size="small"
          :type="checkpointStatus.inProgress ? 'warning' : checkpointStatus.lastStatus === 'err' ? 'danger' : 'success'"
        >
          {{
            checkpointStatus.inProgress
              ? '落盘中'
              : checkpointStatus.lastStatus === 'err'
                ? '上次失败'
                : '就绪'
          }}
        </el-tag>
        <span v-if="checkpointStatus?.lastDurationMillis && checkpointStatus.lastSaveTime > 0" class="muted">
          耗时 {{ checkpointStatus.lastDurationMillis }} ms
        </span>
      </div>
      <el-button size="small" text @click="loadCheckpointStatus">刷新快照状态</el-button>
    </div>

    <div class="panel">
      <el-table :data="rows" size="small">
        <template #empty>
          <EmptyState title="还没有备份" description="点击右上角「立即备份」为该实例创建第一份快照。" />
        </template>
        <el-table-column prop="fileName" label="文件名" min-width="240">
          <template #default="{ row }">
            <span class="mono">{{ row.fileName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="实例" width="100">
          <template #default="{ row }">#{{ row.instanceId }}</template>
        </el-table-column>
        <el-table-column label="键数" width="100">
          <template #default="{ row }">{{ row.keyCount }}</template>
        </el-table-column>
        <el-table-column label="大小" width="110">
          <template #default="{ row }">{{ formatBytes(row.sizeBytes) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="row.status === 'SUCCESS' ? 'success' : row.status === 'FAILED' ? 'danger' : 'warning'"
            >
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180" />
        <el-table-column label="操作" width="230">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="download(row)">下载</el-button>
            <el-button v-if="auth.isAdmin" link type="warning" size="small" :disabled="row.status !== 'SUCCESS'" @click="restore(row)">
              恢复
            </el-button>
            <el-button v-if="auth.canOperate" link type="danger" size="small" @click="remove(row)">删除</el-button>
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
}

.checkpoint-strip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  margin-bottom: 12px;
}

.checkpoint-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.meta-label {
  font-size: 13px;
  color: var(--tk-text-2);
}

.muted {
  font-size: 12px;
  color: var(--tk-text-3);
}
</style>