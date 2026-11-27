<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Plus, Search } from '@element-plus/icons-vue'
import { multiModelApi } from '@/api'
import type { JsonKeysView, JsonQueryView } from '@/api/types'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadge from '@/components/StatusBadge.vue'

const props = defineProps<{
  instanceId?: number
  jsonKeysInfo: JsonKeysView | null
  pattern: string
  canOperate: boolean
}>()

const emit = defineEmits<{
  (e: 'update:pattern', value: string): void
  (e: 'refresh-keys'): void
}>()

const patternModel = computed({
  get: () => props.pattern,
  set: (val: string) => emit('update:pattern', val)
})

const jsonQueryForm = ref({
  key: '',
  path: '$'
})
const jsonQuerying = ref(false)
const jsonResult = ref<JsonQueryView | null>(null)
const jsonEditorText = ref('')
const jsonSaving = ref(false)
const jsonDialogVisible = ref(false)
const jsonCreateForm = ref({
  key: '',
  path: '$',
  value: '{\n  "service": "tiering-kv",\n  "version": "6.2.0",\n  "replicas": 3,\n  "tags": ["lsm", "hnsw"]\n}'
})

watch(
  () => props.instanceId,
  () => {
    jsonResult.value = null
    jsonQueryForm.value.key = ''
  }
)

watch(
  () => props.jsonKeysInfo,
  async (js) => {
    if (js?.supported && js.keys.length > 0 && !jsonQueryForm.value.key) {
      jsonQueryForm.value.key = js.keys[0]
      await queryJson()
    }
  },
  { immediate: true }
)

async function selectJsonKey(key: string) {
  jsonQueryForm.value.key = key
  jsonQueryForm.value.path = '$'
  await queryJson()
}

async function queryJson() {
  if (!props.instanceId) return
  const key = jsonQueryForm.value.key.trim()
  if (!key) {
    ElMessage.warning('请输入 JSON 键名')
    return
  }
  jsonQuerying.value = true
  try {
    const res = await multiModelApi.getJson(
      props.instanceId,
      key,
      jsonQueryForm.value.path.trim() || '$'
    )
    jsonResult.value = res
    jsonEditorText.value = res.prettyJson ?? res.rawJson ?? ''
  } finally {
    jsonQuerying.value = false
  }
}

async function drillIntoField(field: string) {
  const base = jsonQueryForm.value.path.trim() || '$'
  jsonQueryForm.value.path = base === '$' ? `$.${field}` : `${base}.${field}`
  await queryJson()
}

async function saveJsonEdit() {
  if (!props.instanceId) return
  const key = jsonQueryForm.value.key.trim()
  if (!key || !jsonEditorText.value.trim()) {
    ElMessage.warning('请填写键名与 JSON 内容')
    return
  }
  jsonSaving.value = true
  try {
    await multiModelApi.setJson({
      instanceId: props.instanceId,
      key,
      path: jsonQueryForm.value.path.trim() || '$',
      value: jsonEditorText.value
    })
    ElMessage.success('JSON 已保存')
    emit('refresh-keys')
    await queryJson()
  } finally {
    jsonSaving.value = false
  }
}

async function submitCreateJson() {
  if (!props.instanceId) return
  const f = jsonCreateForm.value
  if (!f.key.trim() || !f.value.trim()) {
    ElMessage.warning('请填写键名与 JSON 内容')
    return
  }
  jsonSaving.value = true
  try {
    await multiModelApi.setJson({
      instanceId: props.instanceId,
      key: f.key.trim(),
      path: f.path.trim() || '$',
      value: f.value
    })
    ElMessage.success(`JSON 键 ${f.key.trim()} 已写入`)
    jsonDialogVisible.value = false
    jsonQueryForm.value.key = f.key.trim()
    jsonQueryForm.value.path = '$'
    emit('refresh-keys')
    await queryJson()
  } finally {
    jsonSaving.value = false
  }
}

async function deleteJsonPath() {
  if (!props.instanceId) return
  const key = jsonQueryForm.value.key.trim()
  const path = jsonQueryForm.value.path.trim() || '$'
  if (!key) return
  await ElMessageBox.confirm(`确认删除 ${key} 路径 ${path}？`, '删除 JSON', { type: 'warning' })
  await multiModelApi.deleteJson(props.instanceId, key, path)
  ElMessage.success('已删除')
  emit('refresh-keys')
  await queryJson()
}
</script>

<template>
  <div>
    <div class="split-grid">
      <div class="sub-card">
        <div class="query-bar">
          <el-input v-model="patternModel" placeholder="键过滤模式 (*)" size="small" class="flex-1" />
          <el-button size="small" @click="emit('refresh-keys')">筛选</el-button>
          <el-button
            v-if="canOperate"
            size="small"
            type="primary"
            :disabled="jsonKeysInfo?.supported === false"
            @click="jsonDialogVisible = true"
          >
            <el-icon><Plus /></el-icon> 新建
          </el-button>
        </div>
        <el-table :data="(jsonKeysInfo?.keys ?? []).map((k) => ({ key: k }))" size="small" style="margin-top: 8px">
          <template #empty>
            <EmptyState title="暂无 JSON 键" description="点击「新建」写入第一份 JSON 文档。" />
          </template>
          <el-table-column prop="key" label="JSON 键名">
            <template #default="{ row }">
              <el-button link type="primary" @click="selectJsonKey(row.key)">
                <span class="mono">{{ row.key }}</span>
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="sub-card">
        <h4 class="sub-title">JSONPath 实时查询与原地编辑（JSON.GET / JSON.SET）</h4>
        <div class="query-bar">
          <el-input v-model="jsonQueryForm.key" placeholder="JSON 键名" style="width: 190px" />
          <el-input v-model="jsonQueryForm.path" placeholder="JSONPath，例如 $ 或 $.tags" class="flex-1" />
          <el-button
            type="primary"
            :loading="jsonQuerying"
            :disabled="jsonKeysInfo?.supported === false"
            @click="queryJson"
          >
            <el-icon><Search /></el-icon> 查询
          </el-button>
        </div>

        <div v-if="jsonResult" class="json-meta">
          <StatusBadge
            :label="jsonResult.exists ? (jsonResult.valueType || 'json') : 'NOT FOUND'"
            :tone="jsonResult.exists ? 'accent' : 'warn'"
            mono
          />
          <div v-if="jsonResult.objectKeys.length > 0" class="field-chips">
            <span class="muted">字段下钻：</span>
            <el-button
              v-for="field in jsonResult.objectKeys"
              :key="field"
              size="small"
              text
              bg
              @click="drillIntoField(field)"
            >
              .{{ field }}
            </el-button>
          </div>
        </div>

        <el-input
          v-model="jsonEditorText"
          type="textarea"
          :rows="12"
          placeholder="查询结果将格式化展示在此处，可直接编辑后保存"
          class="json-editor mono"
        />

        <div v-if="canOperate" class="json-actions">
          <el-button
            type="primary"
            :loading="jsonSaving"
            :disabled="jsonKeysInfo?.supported === false || !jsonQueryForm.key.trim()"
            @click="saveJsonEdit"
          >
            保存修改（JSON.SET）
          </el-button>
          <el-button
            type="danger"
            plain
            :disabled="jsonKeysInfo?.supported === false || !jsonQueryForm.key.trim()"
            @click="deleteJsonPath"
          >
            <el-icon><Delete /></el-icon> 删除当前路径
          </el-button>
        </div>
      </div>
    </div>

    <el-dialog v-model="jsonDialogVisible" title="新建 JSON 文档（JSON.SET）" width="520px">
      <el-form label-width="84px" @submit.prevent>
        <el-form-item label="键名">
          <el-input v-model="jsonCreateForm.key" placeholder="例如 doc:profile:1" />
        </el-form-item>
        <el-form-item label="路径">
          <el-input v-model="jsonCreateForm.path" placeholder="$" />
        </el-form-item>
        <el-form-item label="JSON 值">
          <el-input v-model="jsonCreateForm.value" type="textarea" :rows="8" class="mono" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="jsonDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="jsonSaving" @click="submitCreateJson">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.split-grid {
  display: grid;
  grid-template-columns: minmax(260px, 340px) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

@media (max-width: 1024px) {
  .split-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

.sub-card {
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
  background: var(--surface-2);
  padding: 14px;
}

.sub-title {
  margin: 0 0 10px;
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text-1);
}

.query-bar {
  display: flex;
  align-items: center;
  gap: 8px;
}

.flex-1 {
  flex: 1;
  min-width: 140px;
}

.json-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.field-chips {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
}

.muted {
  font-size: var(--text-xs);
  color: var(--text-3);
}

.json-editor {
  margin-top: 10px;
}

.json-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}
</style>
