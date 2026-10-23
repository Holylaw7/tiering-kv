<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { auditApi } from '@/api'
import type { OpLog } from '@/api/types'

const rows = ref<OpLog[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const action = ref('')
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const result = await auditApi.page({ action: action.value || undefined, page: page.value, size: size.value })
    rows.value = result.items
    total.value = result.total
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-header">
      <div>
        <h2 class="page-title">审计日志</h2>
        <p class="page-subtitle">所有写操作、登录行为与危险命令均留痕，支持按动作检索</p>
      </div>
      <div style="display: flex; gap: 10px">
        <el-input v-model="action" placeholder="动作，如 KEY_DELETE" clearable style="width: 220px" @keyup.enter="load">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button @click="load">查询</el-button>
      </div>
    </div>

    <div class="panel">
      <el-table :data="rows" size="small">
        <el-table-column prop="createdAt" label="时间" width="180" />
        <el-table-column prop="username" label="操作人" width="120" />
        <el-table-column prop="action" label="动作" width="190">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.action }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="target" label="对象" min-width="200" show-overflow-tooltip />
        <el-table-column prop="detail" label="详情" min-width="180" show-overflow-tooltip />
        <el-table-column prop="ip" label="来源 IP" width="140" />
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag :type="row.success ? 'success' : 'danger'" size="small">{{ row.success ? '成功' : '失败' }}</el-tag>
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
</style>
