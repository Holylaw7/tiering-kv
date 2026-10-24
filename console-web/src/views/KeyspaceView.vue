<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { keyspaceApi } from '@/api'
import type { KeyDetail, KeyEntry } from '@/api/types'
import { useInstanceStore } from '@/stores/instance'
import { useAuthStore } from '@/stores/auth'
import { formatBytes, formatTtl } from '@/utils/format'

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
      <div class="toolbar">
        <el-input v-model="pattern" placeholder="匹配模式，如 user:*" style="width: 220px" clearable @keyup.enter="scan(true)">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="count" style="width: 110px">
          <el-option :value="20" label="20 条/页" />
          <el-option :value="50" label="50 条/页" />
          <el-option :value="100" label="100 条/页" />
        </el-select>
        <el-button type="primary" @click="scan(true)">扫描</el-button>
      </div>
    </div>

    <div class="panel" v-loading="loading">
      <el-table :data="rows" size="small" height="520">
        <el-table-column prop="key" label="键" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.key }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="typeTagType(row.type)">{{ row.type }}</el-tag>
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
      <div v-loading="detailLoading">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="类型">
            <el-tag size="small">{{ detail?.type }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="过期">{{ formatTtl(detail?.ttl) }}</el-descriptions-item>
        </el-descriptions>

        <div class="section-title">值</div>
        <el-input
          v-if="detail?.type === 'string' && auth.canOperate"
          v-model="editingValue"
          type="textarea"
          :rows="8"
          class="mono"
        />
        <pre v-else class="value-box mono">{{ renderValue() }}</pre>
        <div v-if="detail?.type === 'string' && auth.canOperate" style="margin-top: 10px">
          <el-button type="primary" size="small" @click="saveValue">保存值</el-button>
        </div>

        <div class="section-title">过期时间（秒，-1 表示永久）</div>
        <div style="display: flex; gap: 10px; align-items: center">
          <el-input-number v-model="ttlInput" :min="-1" controls-position="right" :disabled="!auth.canOperate" />
          <el-button v-if="auth.canOperate" size="small" @click="saveTtl">应用</el-button>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
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
