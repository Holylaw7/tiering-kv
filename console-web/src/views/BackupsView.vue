<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { backupApi } from '@/api'
import type { BackupRecord } from '@/api/types'
import { useInstanceStore } from '@/stores/instance'
import { useAuthStore } from '@/stores/auth'
import { formatBytes } from '@/utils/format'

const store = useInstanceStore()
const auth = useAuthStore()
const rows = ref<BackupRecord[]>([])
const loading = ref(false)
const creating = ref(false)

async function load() {
  loading.value = true
  try {
    rows.value = await backupApi.list()
  } finally {
    loading.value = false
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
    ElMessage.success(`备份完成，共 ${record.keyCount} 个键`)
    await load()
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
    .then((resp) => resp.blob())
    .then((blob) => {
      const link = document.createElement('a')
      link.href = URL.createObjectURL(blob)
      link.download = row.fileName
      link.click()
      URL.revokeObjectURL(link.href)
    })
    .catch(() => ElMessage.error('下载失败'))
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-header">
      <div>
        <h2 class="page-title">备份与恢复</h2>
        <p class="page-subtitle">逻辑备份：SCAN 全量导出为 JSONL，按类型回放恢复，过程可审计</p>
      </div>
      <el-button v-if="auth.canOperate" type="primary" :loading="creating" @click="create">
        <el-icon><FolderAdd /></el-icon> 立即备份
      </el-button>
    </div>

    <div class="panel">
      <el-table :data="rows" size="small">
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
