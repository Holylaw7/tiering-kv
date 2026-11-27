<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { auditApi } from '@/api'
import type { OpLog } from '@/api/types'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import PageToolbar from '@/components/PageToolbar.vue'

const AUDIT_ACTIONS = [
  'LOGIN',
  'LOGOUT',
  'PASSWORD_CHANGE',
  'INSTANCE_CREATE',
  'KEY_SET',
  'KEY_DELETE',
  'KEY_EXPIRE',
  'KEY_HSET',
  'TERMINAL_BLOCKED',
  'SLOWLOG_RESET',
  'CREATE_BACKUP',
  'VERIFY_BACKUP',
  'CREATE_SAFETY_SNAPSHOT',
  'RESTORE_BACKUP',
  'DOWNLOAD_BACKUP',
  'BACKUP_CHECKPOINT',
  'ALERT_RULE_CREATE',
  'ALERT_RESOLVE',
  'USER_CREATE',
  'USER_UPDATE',
  'USER_DELETE'
]

function suggestActions(queryString: string, cb: (results: Array<{ value: string }>) => void) {
  const q = queryString.trim().toUpperCase()
  const matched = q ? AUDIT_ACTIONS.filter((a) => a.includes(q)) : AUDIT_ACTIONS
  cb(matched.map((value) => ({ value })))
}

const rows = ref<OpLog[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const action = ref('')
const username = ref('')
const requestId = ref('')
const result = ref<string>('')
const range = ref<[string, string] | null>(null)
const loading = ref(false)
const exporting = ref(false)

function query(pageNo = page.value) {
  return {
    action: action.value || undefined,
    username: username.value || undefined,
    requestId: requestId.value.trim() || undefined,
    success: result.value === '' ? undefined : result.value === 'true',
    from: range.value?.[0] ? `${range.value[0]}T00:00:00` : undefined,
    to: range.value?.[1] ? `${range.value[1]}T23:59:59` : undefined,
    page: pageNo,
    size: size.value
  }
}

async function copyRequestId(id: string) {
  try {
    await navigator.clipboard.writeText(id)
    ElMessage.success('已复制 Request ID')
  } catch {
    requestId.value = id
    ElMessage.info('已填入筛选框')
  }
}

async function load() {
  loading.value = true
  try {
    const data = await auditApi.page(query())
    rows.value = data.items
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function reset() {
  action.value = ''
  username.value = ''
  requestId.value = ''
  result.value = ''
  range.value = null
  page.value = 1
  load()
}

async function exportCsv() {
  exporting.value = true
  try {
    const blob = await auditApi.exportCsv(query(1))
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    const stamp = new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')
    link.href = url
    link.download = `audit-logs-${stamp}.csv`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
    ElMessage.success('已导出当前筛选结果')
  } finally {
    exporting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-header">
      <div>
        <h2 class="page-title">审计日志</h2>
        <p class="page-subtitle">所有写操作、登录行为与危险命令均留痕；支持按动作、操作人、结果与时间区间筛选</p>
      </div>
      <el-button :loading="exporting" @click="exportCsv">导出 CSV</el-button>
    </div>

    <div class="panel">
      <PageToolbar :meta="`共 ${total} 条审计记录`">
        <el-autocomplete
          v-model="action"
          :fetch-suggestions="suggestActions"
          placeholder="动作，如 KEY_DELETE"
          clearable
          style="width: 190px"
          @keyup.enter="load"
        />
        <el-input v-model="username" placeholder="操作人" clearable class="f-user" @keyup.enter="load" />
        <el-input v-model="requestId" placeholder="Request ID" clearable class="f-req mono" @keyup.enter="load" />
        <el-select v-model="result" placeholder="结果" clearable class="f-result">
          <el-option label="成功" value="true" />
          <el-option label="失败" value="false" />
        </el-select>
        <el-date-picker
          v-model="range"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          class="f-range"
        />
        <el-button type="primary" @click="page = 1; load()">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </PageToolbar>
      <el-table :data="rows" size="small">
        <template #empty>
          <EmptyState title="没有匹配的审计记录" description="调整筛选条件，或清空筛选查看全部操作。" />
        </template>
        <el-table-column prop="createdAt" label="时间" width="180" />
        <el-table-column prop="username" label="操作人" width="120" />
        <el-table-column label="动作" width="190">
          <template #default="{ row }">
            <StatusBadge :label="row.action" tone="neutral" mono />
          </template>
        </el-table-column>
        <el-table-column prop="target" label="对象" min-width="200" show-overflow-tooltip />
        <el-table-column prop="detail" label="详情" min-width="180" show-overflow-tooltip />
        <el-table-column prop="ip" label="来源 IP" width="136" />
        <el-table-column label="Request ID" width="150">
          <template #default="{ row }">
            <el-tooltip v-if="row.requestId" :content="`${row.requestId}（点击复制）`" placement="top">
              <el-button
                link
                size="small"
                class="mono req-link"
                @click="copyRequestId(row.requestId)"
              >
                {{ row.requestId.length > 14 ? `${row.requestId.slice(0, 14)}…` : row.requestId }}
              </el-button>
            </el-tooltip>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="94">
          <template #default="{ row }">
            <StatusBadge
              :label="row.success ? '成功' : '失败'"
              :tone="row.success ? 'success' : 'danger'"
              dot
            />
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="prev, pager, next, total"
        class="pager"
        @current-change="load"
      />
    </div>
  </div>
</template>

<style scoped>
.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.f-user {
  width: 124px;
}

.f-req {
  width: 156px;
}

.req-link {
  font-size: 11.5px;
  padding: 0;
}

.muted {
  color: var(--text-3);
}

.f-result {
  width: 110px;
}

.f-range {
  width: 250px;
}
</style>