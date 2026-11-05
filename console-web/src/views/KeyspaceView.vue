<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { keyspaceApi } from '@/api'
import type { KeyDetail, KeyEntry } from '@/api/types'
import { useInstanceStore } from '@/stores/instance'
import { useAuthStore } from '@/stores/auth'
import { formatBytes, formatTtl } from '@/utils/format'
import EmptyState from '@/components/EmptyState.vue'

const store = useInstanceStore()
const auth = useAuthStore()
const pattern = ref('*')
const rows = ref<KeyEntry[]>([])
const loading = ref(false)
const cursor = ref('0')
const finished = ref(true)
const count = ref(50)
const drawerVisible = ref(false)
const detail = ref<KeyDetail>()
const detailLoading = ref(false)
const editingValue = ref('')
const ttlInput = ref<number>(-1)
const hashRows = ref<Array<{ field: string; value: string }>>([])
const newField = ref({ field: '', value: '' })
const hashSaving = ref(false)

function typeTagType(type: string) {
  switch (type) {
    case 'string': return 'success'
    case 'hash': return 'warning'
    case 'list': return 'primary'
    case 'set': return 'info'
    case 'zset': return 'danger'
    default: return 'info'
  }
}

async function scan(reset = true) {
  const id = store.selectedId
  if (!id) return
  loading.value = true
  try {
    const page = await keyspaceApi.scan({
      instanceId: id,
      cursor: reset ? '0' : cursor.value,
      pattern: pattern.value || '*',
      count: count.value
    })
    cursor.value = page.cursor
    finished.value = page.finished
    rows.value = reset ? page.keys : [...rows.value, ...page.keys]
  } finally {
    loading.value = false
  }
}

async function openDetail(row: KeyEntry) {
  const id = store.selectedId
  if (!id) return
  drawerVisible.value = true
  detailLoading.value = true
  try {
    const data = await keyspaceApi.detail(id, row.key)
    detail.value = data
    ttlInput.value = data.ttl
    editingValue.value = typeof data.value === 'string' ? data.value : ''
    hashRows.value =
      data.type === 'hash' && data.value && typeof data.value === 'object'
        ? Object.entries(data.value as Record<string, string>).map(([field, value]) => ({
            field,
            value: String(value)
          }))
        : []
    newField.value = { field: '', value: '' }
  } finally {
    detailLoading.value = false
  }
}

async function saveValue() {
  if (!detail.value) return
  await keyspaceApi.put(store.selectedId, detail.value.key, editingValue.value)
  ElMessage.success('已写入')
  await openDetail(detail.value as KeyEntry)
  await scan(true)
}

async function saveHashField(field: string, value: string) {
  if (!detail.value) return
  if (!field) {
    ElMessage.warning('字段名不能为空')
    return
  }
  hashSaving.value = true
  try {
    await keyspaceApi.putHashField(store.selectedId, detail.value.key, field, value)
    ElMessage.success('字段已保存')
    await openDetail(detail.value as KeyEntry)
    await scan(true)
  } finally {
    hashSaving.value = false
  }
}

async function addHashField() {
  if (!newField.value.field) {
    ElMessage.warning('请填写字段名')
    return
  }
  await saveHashField(newField.value.field, newField.value.value)
}

async function removeHashField(field: string) {
  if (!detail.value) return
  await ElMessageBox.confirm(`确认删除字段 ${field}？`, '危险操作', { type: 'warning' })
  await keyspaceApi.removeHashField(store.selectedId, detail.value.key, field)
  ElMessage.success('字段已删除')
  await openDetail(detail.value as KeyEntry)
  await scan(true)
}

async function saveTtl() {
  if (!detail.value) return
  await keyspaceApi.ttl(store.selectedId, detail.value.key, ttlInput.value)
  ElMessage.success('过期时间已更新')
  await openDetail(detail.value as KeyEntry)
}

async function remove(row: KeyEntry) {
  await ElMessageBox.confirm(`确认删除键 ${row.key}？`, '危险操作', { type: 'warning' })
  await keyspaceApi.remove(store.selectedId, row.key)
  ElMessage.success('已删除')
  await scan(true)
}

function renderValue(): string {
  if (!detail.value) return ''
  const value = detail.value.value
  if (typeof value === 'string') return value
  return JSON.stringify(value, null, 2)
}

watch(() => store.selectedId, () => scan(true))
onMounted(() => scan(true))
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">键空间浏览</h2>
        <p class="page-subtitle">基于 SCAN 游标遍历，不阻塞内核事件循环；键按类型自动识别</p>
      </div>
    </div>

    <div class="panel" v-loading="loading">
      <div class="filters">
        <el-input v-model="pattern" class="f-pattern mono" placeholder="匹配模式，如 user:*" clearable @keyup.enter="scan(true)">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="count" class="f-count">
          <el-option :value="20" label="20 条/页" />
          <el-option :value="50" label="50 条/页" />
          <el-option :value="100" label="100 条/页" />
        </el-select>
        <el-button type="primary" size="small" @click="scan(true)">扫描</el-button>
        <span class="f-hint">SCAN 游标遍历，单次最多 200 条</span>
      </div>
      <el-table :data="rows" size="small">
        <template #empty>
          <EmptyState title="没有扫描到键" description="调整匹配模式后重新扫描。" />
        </template>
        <el-table-column prop="key" label="键" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.key }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag size="small" type="info">{{ row.type }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="过期" width="120">
          <template #default="{ row }">
            <span :class="{ mono: true, muted: row.ttl === -1 }">{{ formatTtl(row.ttl) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">查看</el-button>
            <el-button v-if="auth.canOperate" link type="danger" size="small" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="footer">
        <span class="muted">已加载 {{ rows.length }} 个键{{ finished ? '（已扫描完成）' : '' }}</span>
        <div>
          <el-button size="small" :disabled="finished" @click="scan(false)">继续扫描</el-button>
          <el-button size="small" @click="scan(true)">重新扫描</el-button>
        </div>
      </div>
    </div>

    <el-drawer v-model="drawerVisible" size="560px" :title="detail?.key ?? '键详情'">
      <div v-loading="detailLoading" class="detail">
        <div class="meta">
          <div class="meta-item">
            <span class="meta-label">类型</span>
            <span class="meta-value mono">{{ detail?.type ?? '—' }}</span>
          </div>
          <div class="meta-item">
            <span class="meta-label">过期</span>
            <span class="meta-value">{{ formatTtl(detail?.ttl) }}</span>
          </div>
        </div>

        <section class="block">
          <header class="block-head">
            <h4>值</h4>
            <span class="block-hint">{{ detail?.type === 'string' ? '可直接编辑并保存' : '按类型只读展示' }}</span>
          </header>

          <template v-if="detail?.type === 'string' && auth.canOperate">
            <el-input v-model="editingValue" type="textarea" :rows="9" class="mono value-editor" />
            <div class="block-actions">
              <el-button type="primary" size="small" @click="saveValue">保存值</el-button>
            </div>
          </template>

          <template v-else-if="detail?.type === 'hash' && auth.canOperate">
            <el-table :data="hashRows" size="small" max-height="300">
              <el-table-column prop="field" label="字段" width="150" show-overflow-tooltip>
                <template #default="{ row }"><span class="mono">{{ row.field }}</span></template>
              </el-table-column>
              <el-table-column label="值">
                <template #default="{ row }">
                  <el-input v-model="row.value" size="small" class="mono" />
                </template>
              </el-table-column>
              <el-table-column label="操作" width="120" align="right">
                <template #default="{ row }">
                  <el-button link size="small" :loading="hashSaving" @click="saveHashField(row.field, row.value)">保存</el-button>
                  <el-button link type="danger" size="small" @click="removeHashField(row.field)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div class="hash-add">
              <el-input v-model="newField.field" size="small" placeholder="新字段" class="mono f-name" />
              <el-input v-model="newField.value" size="small" placeholder="值" class="mono" />
              <el-button size="small" :loading="hashSaving" @click="addHashField">新增字段</el-button>
            </div>
          </template>

          <pre v-else class="value-box mono">{{ renderValue() }}</pre>
        </section>

        <section class="block">
          <header class="block-head">
            <h4>过期时间</h4>
            <span class="block-hint">单位秒，-1 表示永久</span>
          </header>
          <div class="ttl-row">
            <el-input-number v-model="ttlInput" :min="-1" controls-position="right" :disabled="!auth.canOperate" />
            <el-button v-if="auth.canOperate" size="small" @click="saveTtl">应用</el-button>
          </div>
        </section>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.detail {
  display: flex;
  flex-direction: column;
  gap: 22px;
}

.meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface-2);
}

.meta-item {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 10px 14px;
}

.meta-item + .meta-item {
  border-left: 1px solid var(--line);
}

.meta-label {
  font-size: var(--text-xs);
  font-weight: 600;
  letter-spacing: 0.05em;
  text-transform: uppercase;
  color: var(--text-3);
}

.meta-value {
  font-size: var(--text-sm);
  color: var(--text-1);
}

.block-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 8px;
}

.block-head h4 {
  margin: 0;
  font-size: var(--text-base);
  font-weight: 600;
  color: var(--text);
}

.block-hint {
  font-size: var(--text-xs);
  color: var(--text-3);
}

.value-editor :deep(.el-textarea__inner) {
  font-size: 12.5px;
  line-height: 1.6;
}

.block-actions {
  margin-top: 10px;
  display: flex;
  justify-content: flex-end;
}

.ttl-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.hash-add {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 10px;
}

.f-name {
  width: 150px;
}

.filters {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  padding-bottom: 14px;
  margin-bottom: 4px;
  border-bottom: 1px solid var(--line-soft);
}

.f-pattern {
  width: 240px;
}

.f-count {
  width: 108px;
}

.f-hint {
  margin-left: auto;
  font-size: var(--text-xs);
  color: var(--text-3);
}

.footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
}

.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.section-title {
  margin: 18px 0 8px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.hash-add {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 10px;
}

.value-box {
  background: #10161d;
  border: 1px solid var(--console-border);
  border-radius: 8px;
  padding: 12px;
  max-height: 320px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  margin: 0;
}
</style>