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
function fillDemo() {
  form.username = 'admin'
  form.password = 'admin123'
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
        <span class="brand-badge">管理系统</span>
      </div>
      <span class="tagline">Spring Boot · Vue 3 · 面向自研键值存储引擎</span>
    </header>

    <main class="stage">
      <section class="intro">
        <div class="eyebrow">控制平面 · 统一纳管平台</div>
        <h1>键值存储实例的<br />统一监控、运维与安全治理。</h1>
        <p class="lede">
          基于 RESP 协议无侵入纳管多节点键值存储实例，集成运行指标观测、键空间维护、双端备份恢复与独立事务审计。
        </p>

        <dl class="facts">
          <div>
            <dt>多实例观测</dt>
            <dd>指标采集三态区分 · 慢查询日志分析</dd>
          </div>
          <div>
            <dt>安全访问</dt>
            <dd>JWT 无状态鉴权 · 三级 RBAC · 终端凭据脱敏</dd>
          </div>
          <div>
            <dt>备份与快照</dt>
            <dd>本地 / S3 对象存储 · BGSAVE 物理 Checkpoint</dd>
          </div>
          <div>
            <dt>审计与告警</dt>
            <dd>规则阈值评估 · 独立事务审计 · RFC 4180 导出</dd>
          </div>
        </dl>
      </section>

      <section class="auth-card">
        <div class="auth-head">
          <h2>登录控制台</h2>
          <p>请使用管理员分配的角色账号认证接入。</p>
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

        <div class="demo-bar">
          <span class="hint">首次登录后请于右上角及时更新默认口令</span>
          <button type="button" class="demo-fill" @click="fillDemo">填入演示凭据</button>
        </div>
      </section>
    </main>

    <footer class="foot">
      <span>Tiering-KV Operations Console</span>
      <span class="mono">Spring Boot 4 · Vue 3 · v6.3.0</span>
    </footer>
  </div>
</template>

<style scoped>
.login {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background: radial-gradient(circle at 12% 14%, rgba(59, 91, 219, 0.05), transparent 40%),
    var(--bg);
  color: var(--text-1);
}

/* ---------- 页眉 ---------- */

.masthead {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 60px;
  padding: 0 48px;
  background: var(--surface);
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

.brand-badge {
  padding: 2px 7px;
  border-radius: 4px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: var(--text-xs);
  font-weight: 500;
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
  gap: 64px;
  max-width: 1180px;
  width: 100%;
  margin: 0 auto;
  padding: 48px 48px 64px;
}

.eyebrow {
  display: inline-block;
  margin-bottom: 12px;
  padding: 3px 9px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface);
  font-size: var(--text-xs);
  font-weight: 500;
  color: var(--text-2);
}

.intro h1 {
  margin: 0;
  font-size: 32px;
  line-height: 1.3;
  font-weight: 600;
  letter-spacing: -0.03em;
  color: var(--text);
}

.lede {
  margin: 16px 0 0;
  max-width: 44ch;
  font-size: var(--text-md);
  line-height: 1.75;
  color: var(--text-2);
}

.facts {
  margin: 36px 0 0;
  display: grid;
  gap: 0;
  max-width: 500px;
}

.facts > div {
  display: grid;
  grid-template-columns: 108px minmax(0, 1fr);
  gap: 16px;
  align-items: baseline;
  padding: 11px 0;
  border-top: 1px solid var(--line-soft);
}

.facts > div:last-child {
  border-bottom: 1px solid var(--line-soft);
}

.facts dt {
  font-size: var(--text-xs);
  font-weight: 600;
  letter-spacing: 0.05em;
  color: var(--text-3);
}

.facts dd {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--text-2);
}

/* ---------- 表单 ---------- */

.auth-card {
  width: 100%;
  max-width: 392px;
  justify-self: end;
  padding: 28px 30px 24px;
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-card);
}

.auth-head h2 {
  margin: 0;
  font-size: var(--text-xl);
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--text);
}

.auth-head p {
  margin: 6px 0 22px;
  font-size: var(--text-sm);
  color: var(--text-2);
}

.submit {
  width: 100%;
  margin-top: 6px;
  height: 40px;
}

.demo-bar {
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid var(--line-soft);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.hint {
  font-size: var(--text-xs);
  line-height: 1.5;
  color: var(--text-3);
}

.demo-fill {
  flex: none;
  padding: 2px 8px;
  border: 1px solid var(--line);
  border-radius: 4px;
  background: var(--surface-2);
  color: var(--text-2);
  font-size: var(--text-xs);
  cursor: pointer;
}

.demo-fill:hover {
  background: var(--surface-3);
  color: var(--accent);
  border-color: var(--accent-line);
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

  .auth-card {
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
