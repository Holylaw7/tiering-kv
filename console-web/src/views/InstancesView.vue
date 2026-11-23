<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus'
import { instanceApi } from '@/api'
import type { InstanceView } from '@/api/types'
import { useInstanceStore } from '@/stores/instance'
import { useAuthStore } from '@/stores/auth'
import StatCard from '@/components/StatCard.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import PageToolbar from '@/components/PageToolbar.vue'

const store = useInstanceStore()
const auth = useAuthStore()
const rows = ref<InstanceView[]>([])
const onlineCount = computed(() => rows.value.filter((row) => row.status === 'UP').length)
const passwordCount = computed(() => rows.value.filter((row) => row.hasPassword).length)
const loading = ref(false)
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const form = reactive({ name: '', host: '127.0.0.1', port: 6379, password: '', remark: '', enabled: true })

const rules = {
  name: [{ required: true, message: '请输入实例名称', trigger: 'blur' }],
  host: [{ required: true, message: '请输入主机地址', trigger: 'blur' }],
  port: [{ required: true, message: '请输入端口', trigger: 'blur' }]
}

async function load() {
  loading.value = true
  try {
    rows.value = await instanceApi.list()
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { name: '', host: '127.0.0.1', port: 6379, password: '', remark: '', enabled: true })
  dialogVisible.value = true
}

function openEdit(row: InstanceView) {
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    host: row.host,
    port: row.port,
    password: '',
    remark: row.remark ?? '',
    enabled: row.enabled
  })
  dialogVisible.value = true
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  const payload = { ...form }
  if (editingId.value) {
    await instanceApi.update(editingId.value, payload)
    ElMessage.success('实例已更新')
  } else {
    await instanceApi.create(payload)
    ElMessage.success('实例已纳管')
  }
  dialogVisible.value = false
  await Promise.all([load(), store.load()])
}

async function probe(row: InstanceView) {
  await instanceApi.probe(row.id)
  ElMessage.success('探测完成')
  await Promise.all([load(), store.load()])
}

async function remove(row: InstanceView) {
  await ElMessageBox.confirm(`确认删除实例 ${row.name}？该操作仅移除纳管关系`, '警告', { type: 'warning' })
  await instanceApi.remove(row.id)
  ElMessage.success('已删除')
  await Promise.all([load(), store.load()])
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-header">
      <div>
        <h2 class="page-title">实例管理</h2>
        <p class="page-subtitle">纳管 Tiering-KV 实例，控制面通过 RESP 协议访问，不依赖内核进程内 API</p>
      </div>
      <el-button v-if="auth.canOperate" type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon> 纳管实例
      </el-button>
    </div>

    <div class="kpi-grid">
      <StatCard label="纳管实例总数" :value="rows.length" hint="RESP 协议直连" />
      <StatCard label="在线 (UP)" :value="onlineCount" tone="success" hint="最近探测可达" />
      <StatCard
        label="离线 / 异常"
        :value="rows.length - onlineCount"
        :tone="rows.length - onlineCount > 0 ? 'danger' : 'default'"
        hint="请检查网络或口令"
      />
      <StatCard label="已配置认证口令" :value="passwordCount" hint="控制台加密托管" />
    </div>

    <div class="panel">
      <PageToolbar meta="控制面通过独立 RESP 连接池访问，不共享存储引擎进程">
        <el-button @click="load">
          <el-icon><Refresh /></el-icon> 刷新列表
        </el-button>
      </PageToolbar>

      <el-table :data="rows" size="default">
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column label="地址" min-width="180">
          <template #default="{ row }">
            <span class="mono">{{ row.host }}:{{ row.port }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <StatusBadge
              :label="row.status"
              :tone="row.status === 'UP' ? 'success' : row.status === 'DOWN' ? 'danger' : 'neutral'"
              dot
              mono
            />
          </template>
        </el-table-column>
        <el-table-column label="版本" width="110">
          <template #default="{ row }">
            <span class="mono">{{ row.version || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="口令" width="90">
          <template #default="{ row }">
            <StatusBadge v-if="row.hasPassword" label="已配置" tone="accent" />
            <span v-else class="muted">无</span>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
        <el-table-column prop="lastProbeAt" label="最近探测" width="170" />
        <el-table-column label="操作" width="170" align="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="probe(row)">探测</el-button>
            <el-button v-if="auth.canOperate" link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="auth.isAdmin" link type="danger" size="small" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑实例' : '纳管实例'" width="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="如 local-6379" />
        </el-form-item>
        <el-form-item label="主机" prop="host">
          <el-input v-model="form.host" placeholder="127.0.0.1" />
        </el-form-item>
        <el-form-item label="端口" prop="port">
          <el-input-number v-model="form.port" :min="1" :max="65535" controls-position="right" />
        </el-form-item>
        <el-form-item label="口令">
          <el-input v-model="form.password" type="password" show-password placeholder="未设置 AUTH 时留空" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" placeholder="可选" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.muted {
  color: var(--text-3);
}
</style>