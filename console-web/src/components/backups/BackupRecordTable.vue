<script setup lang="ts">
import { computed } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import type { BackupRecord } from '@/api/types'
import { formatBytes } from '@/utils/format'
import { restorePercent, shortHash, verifyBadgeLabel, verifyBadgeTone } from '@/utils/backupFormat'
import EmptyState from '@/components/EmptyState.vue'
import PageToolbar from '@/components/PageToolbar.vue'
import StatusBadge from '@/components/StatusBadge.vue'

const props = defineProps<{
  rows: BackupRecord[]
  filteredRows: BackupRecord[]
  statusFilter: string
  canOperate: boolean
  isAdmin: boolean
  verifyingId: number | null
}>()

const emit = defineEmits<{
  (e: 'update:statusFilter', value: string): void
  (e: 'refresh'): void
  (e: 'verify', row: BackupRecord): void
  (e: 'download', row: BackupRecord): void
  (e: 'restore', row: BackupRecord): void
  (e: 'remove', row: BackupRecord): void
}>()

const filterModel = computed({
  get: () => props.statusFilter,
  set: (val: string) => emit('update:statusFilter', val)
})
</script>

<template>
  <div class="panel">
    <PageToolbar :meta="`展示 ${filteredRows.length} / ${rows.length} 条 · 恢复采用合并式幂等回放`">
      <el-select v-model="filterModel" placeholder="全部状态" clearable style="width: 140px">
        <el-option value="SUCCESS" label="SUCCESS（成功）" />
        <el-option value="RUNNING" label="RUNNING（进行中）" />
        <el-option value="FAILED" label="FAILED（失败）" />
      </el-select>
      <el-button @click="emit('refresh')">
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
      <el-table-column label="完整性" width="136">
        <template #default="{ row }">
          <el-tooltip
            v-if="row.sha256"
            :content="`SHA-256: ${row.sha256}${row.verifiedAt ? ' · 校验于 ' + row.verifiedAt : ''}`"
            placement="top"
          >
            <div>
              <StatusBadge
                :label="verifyBadgeLabel(row.verifyStatus)"
                :tone="verifyBadgeTone(row.verifyStatus)"
                dot
              />
              <div class="progress-hint mono">{{ shortHash(row.sha256) }}</div>
            </div>
          </el-tooltip>
          <StatusBadge
            v-else
            :label="verifyBadgeLabel(row.verifyStatus)"
            :tone="verifyBadgeTone(row.verifyStatus)"
            dot
          />
        </template>
      </el-table-column>
      <el-table-column label="恢复进度" width="176">
        <template #default="{ row }">
          <div v-if="row.restoreStatus === 'RESTORING'" class="restore-progress">
            <el-progress :percentage="restorePercent(row as BackupRecord)" :stroke-width="6" />
            <span class="progress-hint mono">{{ row.restoredKeys ?? 0 }} / {{ row.keyCount }}</span>
          </div>
          <div v-else-if="row.restoreStatus === 'SUCCEEDED'">
            <StatusBadge :label="`已恢复 ${row.restoredKeys ?? 0} 键`" tone="success" dot />
            <div v-if="row.preRestoreBackupId" class="progress-hint mono">
              保护快照 #{{ row.preRestoreBackupId }}
            </div>
          </div>
          <el-tooltip
            v-else-if="row.restoreStatus === 'FAILED'"
            :content="row.restoreMessage || '恢复中断'"
            placement="top"
          >
            <StatusBadge :label="`失败 (${row.restoredKeys ?? 0}/${row.keyCount})`" tone="danger" dot />
          </el-tooltip>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="176" />
      <el-table-column label="操作" width="236">
        <template #default="{ row }">
          <el-button
            v-if="canOperate"
            link
            type="primary"
            size="small"
            :loading="verifyingId === row.id"
            :disabled="row.status !== 'SUCCESS'"
            @click="emit('verify', row as BackupRecord)"
          >
            校验
          </el-button>
          <el-button
            v-if="canOperate"
            link
            type="primary"
            size="small"
            :disabled="row.status !== 'SUCCESS'"
            @click="emit('download', row as BackupRecord)"
          >
            下载
          </el-button>
          <el-button
            v-if="isAdmin"
            link
            type="warning"
            size="small"
            :disabled="row.status !== 'SUCCESS' || row.restoreStatus === 'RESTORING'"
            @click="emit('restore', row as BackupRecord)"
          >
            恢复
          </el-button>
          <el-button
            v-if="canOperate"
            link
            type="danger"
            size="small"
            :disabled="row.status === 'RUNNING' || row.restoreStatus === 'RESTORING'"
            @click="emit('remove', row as BackupRecord)"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
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
