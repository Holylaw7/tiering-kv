<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { systemApi } from '@/api'
import type { SstableTable } from '@/api/types'
import { formatBytes, formatNumber } from '@/utils/format'
import { paginate, sortTables } from '@/utils/inventory'

const props = defineProps<{
  modelValue: boolean
  instanceId?: number
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (val: boolean) => emit('update:modelValue', val)
})

const loading = ref(false)
const errorText = ref('')
const tableList = ref<SstableTable[]>([])
const totals = ref({ count: 0, totalBytes: 0, totalEntries: 0, keyOrder: 'unknown' })
const sortKey = ref<'id' | 'keys' | 'bytes'>('id')
const sortOrder = ref<'asc' | 'desc'>('asc')
const page = ref(1)
const pageSize = 50

const sortedTables = computed(() => sortTables(tableList.value, sortKey.value, sortOrder.value))
const pagedTables = computed(() => paginate(sortedTables.value, page.value, pageSize))

const keyOrderLabel = computed(() =>
  totals.value.keyOrder === 'bytewise' ? 'BYTEWISE ORDER' : '排序语义未知'
)
const keyOrderHint = computed(() =>
  totals.value.keyOrder === 'bytewise'
    ? '键范围为无符号字节字典序：tier:1000000 < tier:999999，界面不会按数值重排'
    : '内核未声明键比较器语义，列表按 ID 排序，键范围原样展示'
)

function onSortChange({ prop, order }: { prop: string | null; order: string | null }) {
  sortKey.value = (prop as 'id' | 'keys' | 'bytes' | null) ?? 'id'
  sortOrder.value = order === 'descending' ? 'desc' : 'asc'
  page.value = 1
}

async function fetchTables() {
  if (!props.instanceId) return
  loading.value = true
  errorText.value = ''
  tableList.value = []
  totals.value = { count: 0, totalBytes: 0, totalEntries: 0, keyOrder: 'unknown' }
  page.value = 1
  try {
    const view = await systemApi.tables(props.instanceId)
    tableList.value = view.tables
    totals.value = {
      count: view.count,
      totalBytes: view.totalBytes,
      totalEntries: view.totalEntries,
      keyOrder: view.keyOrder ?? 'unknown'
    }
  } catch (ex: unknown) {
    errorText.value = ex instanceof Error ? ex.message : '读取失败'
  } finally {
    loading.value = false
  }
}

async function copyRange(row: SstableTable) {
  const text = 'first_b64=' + row.firstKeyBase64 + '\nlast_b64=' + row.lastKeyBase64
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制原始 Key（Base64）')
  } catch {
    ElMessage.error('复制失败：浏览器未授权剪贴板')
  }
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      void fetchTables()
    }
  }
)
</script>

<template>
  <el-drawer v-model="visible" size="760px" title="冷层 SSTable 清单">
    <div v-loading="loading" class="tables">
      <div class="tables-head">
        <p class="tables-note">
          数据来自 <span class="mono">INFO sstables</span>（按需读取）。键数为表条目累加，
          含历史版本，不是去重后的键数。
        </p>
        <span class="order-badge" :title="keyOrderHint">{{ keyOrderLabel }}</span>
      </div>
      <div v-if="errorText" class="tables-error">{{ errorText }}</div>
      <template v-else>
        <div class="tables-summary">
          <span>{{ totals.count }} 张表</span>
          <span class="num">{{ formatBytes(totals.totalBytes) }}</span>
          <span class="num">{{ formatNumber(totals.totalEntries) }} entries</span>
          <span v-if="tableList.length > pageSize" class="tables-hint">
            排序作用于全部 {{ tableList.length }} 张表，分页仅影响展示
          </span>
        </div>
        <el-table
          :data="pagedTables"
          size="small"
          empty-text="冷层暂无 SSTable"
          :default-sort="{ prop: 'id', order: 'ascending' }"
          @sort-change="onSortChange"
        >
          <el-table-column prop="id" label="ID" width="86" sortable="custom">
            <template #default="{ row }"><span class="mono">#{{ row.id }}</span></template>
          </el-table-column>
          <el-table-column prop="keys" label="键数" width="118" align="right" sortable="custom">
            <template #default="{ row }"><span class="num">{{ formatNumber(row.keys) }}</span></template>
          </el-table-column>
          <el-table-column prop="bytes" label="大小" width="110" align="right" sortable="custom">
            <template #default="{ row }"><span class="num">{{ formatBytes(row.bytes) }}</span></template>
          </el-table-column>
          <el-table-column label="键范围（字节序）" min-width="250">
            <template #default="{ row }">
              <el-tooltip placement="top-start" :show-after="300">
                <template #content>
                  <div class="range-full">
                    <div>first: {{ row.firstKey || '(空)' }}</div>
                    <div>last: {{ row.lastKey || '(空)' }}</div>
                    <div class="range-raw">first_b64: {{ row.firstKeyBase64 || '(空)' }}</div>
                    <div class="range-raw">last_b64: {{ row.lastKeyBase64 || '(空)' }}</div>
                  </div>
                </template>
                <span class="mono range">{{ row.firstKey || '—' }} … {{ row.lastKey || '—' }}</span>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="96" align="right">
            <template #default="{ row }">
              <button class="link-btn" type="button" @click="copyRange(row as SstableTable)">复制 Key</button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-if="tableList.length > pageSize"
          v-model:current-page="page"
          class="tables-pager"
          layout="prev, pager, next"
          :page-size="pageSize"
          :total="tableList.length"
        />
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.tables {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.tables-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.tables-note {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--text-3);
}

.order-badge {
  flex: none;
  padding: 2px 8px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface-2);
  font-size: 10.5px;
  letter-spacing: 0.04em;
  color: var(--text-2);
}

.tables-error {
  padding: 12px 14px;
  border: 1px solid #f3cfcf;
  border-radius: var(--radius-sm);
  background: var(--danger-soft);
  color: var(--danger);
  font-size: 12px;
}

.tables-summary {
  display: flex;
  gap: 18px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--line-soft);
  font-size: 12px;
  color: var(--text-2);
}

.tables-hint {
  margin-left: auto;
  color: var(--text-3);
}

.tables-pager {
  justify-content: flex-end;
}

.range {
  font-size: 11.5px;
  color: var(--text-2);
}

.range-full {
  max-width: 420px;
  font-size: 11.5px;
  line-height: 1.6;
  word-break: break-all;
}

.range-raw {
  color: rgba(255, 255, 255, 0.6);
  font-family: var(--font-mono, monospace);
}

.link-btn {
  border: 0;
  padding: 0;
  background: transparent;
  color: var(--accent);
  font-size: inherit;
  cursor: pointer;
}

.link-btn:hover {
  text-decoration: underline;
}
</style>
