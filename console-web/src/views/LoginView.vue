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
    <section class="brand-panel">
      <div class="brand-top">
        <svg class="mark" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M4 7.5 12 3l8 4.5v9L12 21l-8-4.5v-9Z" stroke="currentColor" stroke-width="1.5" />
          <path d="M4 7.5 12 12l8-4.5M12 12v9" stroke="currentColor" stroke-width="1.5" />
        </svg>
        <span class="name">Tiering-KV</span>
      </div>

      <div class="brand-copy">
        <h1>冷热分层键值数据库<br />运维控制台</h1>
        <p>
          内存热层 + SSTable 冷层，超水位自动迁移；控制台提供实例管理、指标采集、
          键空间维护、在线终端、告警与备份。
        </p>
      </div>

      <dl class="facts">
        <div>
          <dt>命令</dt>
          <dd class="num">157</dd>
        </div>
        <div>
          <dt>协议</dt>
          <dd>RESP2</dd>
        </div>
        <div>
          <dt>运行</dt>
          <dd class="num">JDK 21</dd>
        </div>
      </dl>
    </section>

    <section class="form-panel">
      <div class="form-inner">
        <h2>登录</h2>
        <p class="lead">使用控制台账号继续</p>

        <el-form ref="formRef" :model="form" :rules="rules" @keyup.enter="submit">
          <el-form-item prop="username" label="用户名">
            <el-input v-model="form.username" autocomplete="username" placeholder="admin" />
          </el-form-item>
          <el-form-item prop="password" label="密码">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              autocomplete="current-password"
              placeholder="请输入密码"
            />
          </el-form-item>
          <el-button
            type="primary"
            class="submit"
            data-testid="login-submit"
            :loading="loading"
            @click="submit"
          >
            登录
          </el-button>
        </el-form>

        <p class="tip">默认账号 <span class="mono">admin / admin123</span>，首次登录后请在右上角修改口令。</p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(0, 0.9fr);
  min-height: 100vh;
  background: var(--bg);
}

/* ---------- 左侧品牌区 ---------- */

.brand-panel {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 40px 56px 44px;
  border-right: 1px solid var(--line);
  background-color: var(--bg-subtle);
  background-image:
    radial-gradient(circle at 22% 38%, rgba(76, 141, 255, 0.07), transparent 46%),
    repeating-linear-gradient(0deg, rgba(255, 255, 255, 0.022) 0 1px, transparent 1px 72px),
    repeating-linear-gradient(90deg, rgba(255, 255, 255, 0.022) 0 1px, transparent 1px 72px);
}

.brand-top {
  display: flex;
  align-items: center;
  gap: 9px;
}

.mark {
  width: 20px;
  height: 20px;
  color: var(--accent);
}

.name {
  font-size: 13px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.brand-copy {
  max-width: 30rem;
}

.brand-copy h1 {
  margin: 0;
  font-size: 28px;
  line-height: 1.28;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--text);
}

.brand-copy p {
  margin: 14px 0 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-2);
}

.facts {
  display: flex;
  gap: 40px;
  margin: 0;
}

.facts dt {
  font-size: 11px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--text-3);
}

.facts dd {
  margin: 4px 0 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--text);
}

/* ---------- 右侧表单 ---------- */

.form-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
}

.form-inner {
  width: 100%;
  max-width: 320px;
}

.form-inner h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.lead {
  margin: 6px 0 22px;
  font-size: 12.5px;
  color: var(--text-2);
}

.submit {
  width: 100%;
  height: 34px;
  margin-top: 4px;
}

.tip {
  margin: 22px 0 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--text-3);
}

:deep(.el-form-item__label) {
  font-size: 12px;
  color: var(--text-2);
}

/* 登录页只有两个字段，隐藏必填星号以减少噪声 */
:deep(.el-form-item.is-required:not(.is-no-asterisk) > .el-form-item__label::before) {
  content: '';
  margin: 0;
}

:deep(.el-form-item) {
  margin-bottom: 16px;
}

@media (max-width: 900px) {
  .login {
    grid-template-columns: 1fr;
  }

  .brand-panel {
    display: none;
  }
}
</style>
