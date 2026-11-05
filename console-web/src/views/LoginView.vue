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
    <header class="masthead">
      <div class="brand">
        <svg class="mark" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M4 7.5 12 3l8 4.5v9L12 21l-8-4.5v-9Z" stroke="currentColor" stroke-width="1.5" />
          <path d="M4 7.5 12 12l8-4.5M12 12v9" stroke="currentColor" stroke-width="1.5" />
        </svg>
        <span>Tiering-KV</span>
      </div>
      <span class="tagline">Redis 协议兼容 · 冷热分层存储</span>
    </header>

    <main class="stage">
      <section class="intro">
        <h1>把热数据留在内存，<br />把冷数据交给磁盘。</h1>
        <p class="lede">
          内存层与 SSTable 冷层协同工作：写满水位自动迁移，读路径回退冷层。
          控制台负责实例、键空间、终端、告警与备份的日常运维。
        </p>

        <dl class="facts">
          <div>
            <dt>存储引擎</dt>
            <dd>LSM-Tree · WAL · BlockCache</dd>
          </div>
          <div>
            <dt>协议</dt>
            <dd>RESP2 / RESP3 · 157 条命令</dd>
          </div>
          <div>
            <dt>运行时</dt>
            <dd>Java 21 · Netty</dd>
          </div>
        </dl>
      </section>

      <section class="auth">
        <div class="auth-head">
          <h2>登录控制台</h2>
          <p>使用管理员分配的账号继续。</p>
        </div>

        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="submit">
          <el-form-item prop="username" label="用户名">
            <el-input v-model="form.username" size="large" autocomplete="username" placeholder="admin" />
          </el-form-item>
          <el-form-item prop="password" label="密码">
            <el-input
              v-model="form.password"
              size="large"
              type="password"
              show-password
              autocomplete="current-password"
              placeholder="请输入密码"
            />
          </el-form-item>
          <el-button
            type="primary"
            size="large"
            class="submit"
            data-testid="login-submit"
            :loading="loading"
            @click="submit"
          >
            登录
          </el-button>
        </el-form>

        <p class="hint">
          默认账号 <span class="mono">admin / admin123</span>；首次登录后请在右上角修改口令。
        </p>
      </section>
    </main>

    <footer class="foot">
      <span>Tiering-KV Console</span>
      <span class="mono">v5.1.0</span>
    </footer>
  </div>
</template>

<style scoped>
.login {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background: var(--surface);
  color: var(--text-1);
}

/* ---------- 页眉 ---------- */

.masthead {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 64px;
  padding: 0 48px;
  border-bottom: 1px solid var(--line-soft);
  flex: none;
}

.brand {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: var(--text-md);
  font-weight: 600;
  letter-spacing: -0.015em;
}

.mark {
  width: 20px;
  height: 20px;
  color: var(--accent);
}

.tagline {
  font-size: var(--text-sm);
  color: var(--text-3);
}

/* ---------- 主体 ---------- */

.stage {
  flex: 1;
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(360px, 0.85fr);
  align-items: center;
  gap: 80px;
  max-width: 1180px;
  width: 100%;
  margin: 0 auto;
  padding: 64px 48px 72px;
}

.intro h1 {
  margin: 0;
  font-size: 34px;
  line-height: 1.28;
  font-weight: 600;
  letter-spacing: -0.03em;
  color: var(--text);
}

.lede {
  margin: 18px 0 0;
  max-width: 40ch;
  font-size: var(--text-md);
  line-height: 1.75;
  color: var(--text-2);
}

.facts {
  margin: 44px 0 0;
  display: grid;
  gap: 0;
  max-width: 460px;
}

.facts > div {
  display: grid;
  grid-template-columns: 96px minmax(0, 1fr);
  gap: 16px;
  align-items: baseline;
  padding: 12px 0;
  border-top: 1px solid var(--line-soft);
}

.facts > div:last-child {
  border-bottom: 1px solid var(--line-soft);
}

.facts dt {
  font-size: var(--text-xs);
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--text-3);
}

.facts dd {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--text-2);
}

/* ---------- 表单 ---------- */

.auth {
  width: 100%;
  max-width: 352px;
  justify-self: end;
}

.auth-head h2 {
  margin: 0;
  font-size: var(--text-xl);
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--text);
}

.auth-head p {
  margin: 6px 0 26px;
  font-size: var(--text-sm);
  color: var(--text-2);
}

.submit {
  width: 100%;
  margin-top: 6px;
  height: 40px;
}

.hint {
  margin: 22px 0 0;
  font-size: var(--text-sm);
  line-height: 1.7;
  color: var(--text-3);
}

:deep(.el-form-item) {
  margin-bottom: 18px;
}

:deep(.el-form-item__label) {
  padding-bottom: 6px;
  font-size: var(--text-sm);
  color: var(--text-2);
}

:deep(.el-form-item.is-required:not(.is-no-asterisk) > .el-form-item__label::before) {
  content: '';
  margin: 0;
}

:deep(.el-input__wrapper) {
  height: 40px;
}

/* ---------- 页脚 ---------- */

.foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
  padding: 0 48px;
  border-top: 1px solid var(--line-soft);
  font-size: var(--text-xs);
  color: var(--text-3);
  flex: none;
}

@media (max-width: 960px) {
  .stage {
    grid-template-columns: 1fr;
    gap: 40px;
    padding: 40px 28px 48px;
  }

  .auth {
    justify-self: start;
    max-width: 420px;
  }

  .masthead,
  .foot {
    padding: 0 28px;
  }

  .tagline {
    display: none;
  }

  .intro h1 {
    font-size: 26px;
  }

  .facts {
    margin-top: 28px;
  }
}
</style>
