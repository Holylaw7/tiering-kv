<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, type FormInstance } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({ username: 'admin', password: '' })

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function submit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    ElMessage.success('登录成功')
    const redirect = (route.query.redirect as string) || '/dashboard'
    router.push(redirect)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login">
    <div class="glow"></div>
    <div class="card">
      <div class="brand">
        <span class="mark">⚡</span>
        <div>
          <h1>Tiering-KV</h1>
          <p>冷热分层键值数据库 · 运维管理平台</p>
        </div>
      </div>
      <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="submit">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" autocomplete="username">
            <template #prefix><el-icon><User /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="密码"
            autocomplete="current-password"
          >
            <template #prefix><el-icon><Lock /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-button type="primary" class="submit" :loading="loading" @click="submit">
          登 录
        </el-button>
      </el-form>
      <div class="tip">默认账号 admin / admin123，首次登录后请立即修改口令</div>
    </div>
  </div>
</template>

<style scoped>
.login {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background:
    radial-gradient(circle at 20% 20%, rgba(53, 201, 139, 0.16), transparent 45%),
    radial-gradient(circle at 80% 75%, rgba(64, 158, 255, 0.14), transparent 42%),
    #0b1014;
  position: relative;
  overflow: hidden;
}

.card {
  width: 400px;
  padding: 38px 36px 30px;
  border-radius: 16px;
  background: rgba(22, 28, 36, 0.92);
  border: 1px solid #263140;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.55);
  backdrop-filter: blur(8px);
  z-index: 1;
}

.brand {
  display: flex;
  gap: 14px;
  align-items: center;
  margin-bottom: 28px;
}

.mark {
  font-size: 34px;
}

h1 {
  margin: 0;
  font-size: 22px;
  letter-spacing: 1px;
}

p {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.submit {
  width: 100%;
  margin-top: 6px;
  letter-spacing: 4px;
}

.tip {
  margin-top: 18px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  text-align: center;
}
</style>
