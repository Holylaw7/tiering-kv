<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Calendar, VideoPlay } from '@element-plus/icons-vue'
import type {
  BackupScheduleMode,
  BackupScheduleRequest,
  BackupScheduleView,
  InstanceView
} from '@/api/types'
import EmptyState from '@/components/EmptyState.vue'
import PageToolbar from '@/components/PageToolbar.vue'
import StatusBadge from '@/components/StatusBadge.vue'

const props = defineProps<{
  schedules: BackupScheduleView[]
  instances: InstanceView[]
  selectedInstanceId?: number
  canOperate: boolean
  saving: boolean
  runningScheduleId: number | null
  dialogVisible: boolean
}>()

const emit = defineEmits<{
  (e: 'update:dialogVisible', value: boolean): void
  (e: 'save', editingId: number | null, payload: BackupScheduleRequest): void
  (e: 'toggle', row: BackupScheduleView): void
  (e: 'remove', row: BackupScheduleView): void
  (e: 'run-now', row: BackupScheduleView): void
}>()

const CRON_PRESETS = [
  { label: '每天 02:00', value: '0 2 * * *' },
  { label: '每 6 小时', value: '0 */6 * * *' },
  { label: '每小时整点', value: '0 * * * *' },
  { label: '每周日 03:00', value: '0 3 * * SUN' }
] as const

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

const enabledScheduleCount = computed(() => props.schedules.filter((s) => s.enabled).length)
const visible = computed({
  get: () => props.dialogVisible,
  set: (val: boolean) => emit('update:dialogVisible', val)
})

function openCreateSchedule() {
  editingScheduleId.value = null
  scheduleForm.value = {
    instanceId: props.selectedInstanceId ?? props.instances[0]?.id,
    name: '',
    cronExpression: '0 2 * * *',
    mode: 'LOGICAL',
    retentionCount: 7,
    enabled: true
  }
  visible.value = true
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
  visible.value = true
}

function submitSchedule() {
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
  emit('save', editingScheduleId.value, {
    instanceId: f.instanceId,
    name: f.name.trim(),
    cronExpression: f.cronExpression.trim(),
    mode: f.mode,
    retentionCount: f.retentionCount,
    enabled: f.enabled
  })
}
</script>

<template>
  <div class="panel schedules-panel">
    <PageToolbar :meta="`定时备份策略 · 已启用 ${enabledScheduleCount} / ${schedules.length} 条（多副本 HA 租约单活调度）`">
      <el-button v-if="canOperate" type="primary" plain @click="openCreateSchedule">
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
            v-if="canOperate"
            link
            type="primary"
            size="small"
            :loading="runningScheduleId === row.id"
            @click="emit('run-now', row as BackupScheduleView)"
          >
            <el-icon><VideoPlay /></el-icon> 立即执行
          </el-button>
          <el-button
            v-if="canOperate"
            link
            type="primary"
            size="small"
            @click="openEditSchedule(row as BackupScheduleView)"
          >
            编辑
          </el-button>
          <el-button
            v-if="canOperate"
            link
            size="small"
            @click="emit('toggle', row as BackupScheduleView)"
          >
            {{ row.enabled ? '停用' : '启用' }}
          </el-button>
          <el-button
            v-if="canOperate"
            link
            type="danger"
            size="small"
            @click="emit('remove', row as BackupScheduleView)"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="visible"
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
              v-for="inst in instances"
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
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitSchedule">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.schedules-panel {
  margin-bottom: 14px;
}

.preset-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}

.muted {
  font-size: var(--text-xs);
  color: var(--text-3);
}
</style>
