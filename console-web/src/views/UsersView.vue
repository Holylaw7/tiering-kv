<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus'
import { userApi } from '@/api'
import type { UserView } from '@/api/types'

const rows = ref<UserView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const loading = ref(false)
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()

const form = reactive({ username: '', password: '', nickname: '', email: '', role: 'VIEWER' })
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }]
}

async function load() {
  loading.value = true
  try {
    const result = await userApi.page({ keyword: keyword.value || undefined, page: page.value, size: size.value })
    rows.value = result.items
    total.value = result.total
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { username: '', password: '', nickname: '', email: '', role: 'VIEWER' })
  dialogVisible.value = true
}

function openEdit(row: UserView) {
  editingId.value = row.id
  Object.assign(form, {
    username: row.username,
    password: '',
    nickname: row.nickname ?? '',
    email: row.email ?? '',
    role: row.role
  })
  dialogVisible.value = true
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (editingId.value) {
    await userApi.update(editingId.value, {
      nickname: form.nickname,
      email: form.email || null,
      role: form.role
    })
    ElMessage.success('用户已更新')
  } else {
    await userApi.create({ ...form })
    ElMessage.success('用户已创建')
  }
  dialogVisible.value = false
  await load()
}

async function resetPassword(row: UserView) {
  const { value } = await ElMessageBox.prompt(`为 ${row.username} 设置新口令`, '重置口令', {
    inputType: 'password',
    inputPattern: /^.{6,64}$/,
    inputErrorMessage: '密码长度需在 6-64 之间'
  })
  await userApi.resetPassword(row.id, value)
  ElMessage.success('口令已重置')
}

async function remove(row: UserView) {
  await ElMessageBox.confirm(`确认删除用户 ${row.username}？`, '提示', { type: 'warning' })
  await userApi.remove(row.id)
  ElMessage.success('已删除')
  await load()
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-header">
      <div>
        <h2 class="page-title">用户管理</h2>
        <p class="page-subtitle">ADMIN 全权 / OPS 运维读写 / VIEWER 只读，删除与降级受"最后一个管理员"保护</p>
      </div>
      <div style="display: flex; gap: 10px">
        <el-input v-model="keyword" placeholder="搜索用户名" clearable style="width: 200px" @keyup.enter="load" />
        <el-button @click="load">查询</el-button>
        <el-button type="primary" @click="openCreate"><el-icon><Plus /></el-icon> 新建</el-button>
      </div>
    </div>

    <div class="panel">
      <el-table :data="rows" size="small">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="username" label="用户名" width="140" />
        <el-table-column prop="nickname" label="昵称" width="140" />
        <el-table-column prop="email" label="邮箱" min-width="180" />
        <el-table-column label="角色" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.role === 'ADMIN' ? 'danger' : row.role === 'OPS' ? 'warning' : 'info'">
              {{ row.role }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastLoginAt" label="最近登录" width="180" />
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="warning" size="small" @click="resetPassword(row)">重置口令</el-button>
            <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
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

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑用户' : '新建用户'" width="460px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :disabled="!!editingId" />
        </el-form-item>
        <el-form-item v-if="!editingId" label="口令" prop="password">
          <el-input v-model="form.password" type="password" show-password />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.nickname" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-select v-model="form.role" style="width: 100%">
            <el-option value="ADMIN" label="ADMIN 管理员" />
            <el-option value="OPS" label="OPS 运维" />
            <el-option value="VIEWER" label="VIEWER 只读" />
          </el-select>
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
.pager {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
