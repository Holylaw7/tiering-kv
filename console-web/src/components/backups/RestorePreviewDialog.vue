<script setup lang="ts">
import { computed } from 'vue'
import type { BackupRecord, RestorePreviewView } from '@/api/types'
import { verifyBadgeLabel, verifyBadgeTone } from '@/utils/backupFormat'
import StatusBadge from '@/components/StatusBadge.vue'

const props = defineProps<{
  modelValue: boolean
  target: BackupRecord | null
  preview: RestorePreviewView | null
  previewLoading: boolean
  submitting: boolean
  createSafetySnapshot: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'update:createSafetySnapshot', value: boolean): void
  (e: 'confirm'): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (val: boolean) => emit('update:modelValue', val)
})

const safetySnapshotModel = computed({
  get: () => props.createSafetySnapshot,
  set: (val: boolean) => emit('update:createSafetySnapshot', val)
})
</script>

<template>
  <el-dialog
    v-model="visible"
    title="安全恢复向导（Dry-Run 冲突预览）"
    width="560px"
  >
    <div v-loading="previewLoading" class="restore-wizard">
      <div v-if="target" class="wizard-meta">
        <span>目标归档：</span>
        <span class="mono">{{ target.fileName }}</span>
        <StatusBadge
          :label="verifyBadgeLabel(preview?.verifyStatus ?? target.verifyStatus)"
          :tone="verifyBadgeTone(preview?.verifyStatus ?? target.verifyStatus)"
          dot
        />
      </div>

      <el-alert
        v-if="preview?.warning"
        :type="preview.verifyStatus === 'CORRUPTED' || !preview.archiveReadable ? 'error' : 'warning'"
        :title="preview.warning"
        :closable="false"
        show-icon
        style="margin: 12px 0"
      />

      <div v-if="preview" class="preview-grid">
        <div class="preview-stat">
          <span class="preview-label">归档总键数</span>
          <strong class="num">{{ preview.totalKeys }}</strong>
        </div>
        <div class="preview-stat preview-conflict">
          <span class="preview-label">覆盖冲突键</span>
          <strong class="num">{{ preview.conflictKeys }}</strong>
        </div>
        <div class="preview-stat preview-new">
          <span class="preview-label">预计新增键</span>
          <strong class="num">{{ preview.newKeys }}</strong>
        </div>
        <div class="preview-stat">
          <span class="preview-label">已过期跳过</span>
          <strong class="num">{{ preview.expiredKeys }}</strong>
        </div>
      </div>

      <div v-if="preview && Object.keys(preview.typeBreakdown).length > 0" class="preview-section">
        <div class="preview-section-title">数据类型分布</div>
        <div class="chip-row">
          <StatusBadge
            v-for="(cnt, typeName) in preview.typeBreakdown"
            :key="typeName"
            :label="`${typeName}: ${cnt}`"
            tone="neutral"
            mono
          />
        </div>
      </div>

      <div v-if="preview && preview.sampleConflicts.length > 0" class="preview-section">
        <div class="preview-section-title">将被覆盖的冲突键样例（最多展示 20 条）</div>
        <div class="conflict-list mono">
          <span v-for="k in preview.sampleConflicts" :key="k" class="conflict-key">{{ k }}</span>
        </div>
      </div>

      <div class="safety-option">
        <el-checkbox v-model="safetySnapshotModel">
          恢复前自动创建安全保护快照（强烈推荐，失败或误覆盖时可回滚至保护点）
        </el-checkbox>
      </div>
    </div>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button
        type="warning"
        :loading="submitting"
        :disabled="previewLoading || !preview?.archiveReadable"
        @click="emit('confirm')"
      >
        确认执行恢复
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.wizard-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: var(--text-sm);
  color: var(--text-2);
}

.preview-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin: 12px 0;
}

.preview-stat {
  padding: 10px 12px;
  border: 1px solid var(--border-1);
  border-radius: var(--radius-sm);
  background: var(--bg-subtle);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.preview-label {
  font-size: var(--text-xs);
  color: var(--text-3);
}

.preview-conflict strong {
  color: var(--warn);
}

.preview-new strong {
  color: var(--success);
}

.preview-section {
  margin-top: 12px;
}

.preview-section-title {
  font-size: var(--text-xs);
  color: var(--text-3);
  margin-bottom: 6px;
}

.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.conflict-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 110px;
  overflow-y: auto;
  padding: 8px;
  border: 1px solid var(--border-1);
  border-radius: var(--radius-sm);
  background: var(--bg-subtle);
}

.conflict-key {
  font-size: var(--text-xs);
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--bg-card);
  border: 1px solid var(--border-1);
}

.safety-option {
  margin-top: 14px;
  padding-top: 10px;
  border-top: 1px solid var(--border-1);
}
</style>
