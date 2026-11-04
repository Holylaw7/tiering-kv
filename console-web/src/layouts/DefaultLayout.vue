<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useInstanceStore } from '@/stores/instance'
import { authApi } from '@/api'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const instanceStore = useInstanceStore()

const collapsed = ref(false)

const GROUP_ORDER = ['概览', '数据', '运维', '系统'] as const
const GROUP_OF: Record<string, string> = {
  '/dashboard': '概览',
  '/keyspace': '数据',
  '/terminal': '数据',
  '/instances': '运维',
  '/alerts': '运维',
  '/backups': '运维',
  '/audit': '运维',
  '/system': '系统',
  '/users': '系统'
}

const navGroups = computed(() => {
  const root = router.getRoutes().find((item) => item.path === '/')
  const children = (root?.children ?? []).filter(
    (item) => !item.meta?.adminOnly || auth.isAdmin
  )
  const groups = new Map<string, Array<{ path: string; title: string; icon: string }>>()
  for (const item of children) {
    const path = '/' + item.path
    const group = GROUP_OF[path] ?? '其他'
    const bucket = groups.get(group) ?? []
    bucket.push({ path, title: String(item.meta?.title ?? ''), icon: String(item.meta?.icon ?? 'Menu') })
    groups.set(group, bucket)
  }
  return GROUP_ORDER.filter((g) => groups.has(g)).map((g) => ({ name: g, items: groups.get(g)! }))
})

const currentTitle = computed(() => String(route.meta?.title ?? ''))

// 自助修改口令
const passwordVisible = ref(false)
const passwordLoading = ref(false)
const passwordForm = reactive({ oldPassword: '', newPassword: '', confirm: '' })

function openPassword() {
  passwordForm.oldPassword = ''
  passwordForm.newPassword = ''
  passwordForm.confirm = ''
  passwordVisible.value = true
}

async function submitPassword() {
  if (!passwordForm.oldPassword || !passwordForm.newPassword) {
    ElMessage.warning('请填写原口令与新口令')
    return
  }
  if (passwordForm.newPassword.length < 6) {
    ElMessage.warning('新口令至少 6 位')
    return
  }
  if (passwordForm.newPassword !== passwordForm.confirm) {
    ElMessage.warning('两次输入的新口令不一致')
    return
  }
  passwordLoading.value = true
  try {
    await authApi.changePassword(passwordForm.oldPassword, passwordForm.newPassword)
    ElMessage.success('口令已更新')
    passwordVisible.value = false
  } finally {
    passwordLoading.value = false
  }
}

onMounted(async () => {
  await instanceStore.load()
})

async function handleCommand(command: string) {
  if (command === 'password') {
    openPassword()
    return
  }
  if (command === 'logout') {
    auth.logout()
    router.push('/login')
  }
}

function onInstanceChange(value: number) {
  instanceStore.select(value)
  ElMessage.success('已切换实例')
}
</script>

<template>
  <div class="shell" :class="{ collapsed }">
    <aside class="sidebar">
      <div class="brand">
        <svg class="mark" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M4 7.5 12 3l8 4.5v9L12 21l-8-4.5v-9Z" stroke="currentColor" stroke-width="1.5" />
          <path d="M4 7.5 12 12l8-4.5M12 12v9" stroke="currentColor" stroke-width="1.5" />
        </svg>
        <div v-if="!collapsed" class="brand-text">
          <span class="word">Tiering-KV</span>
          <span class="sub">Console</span>
        </div>
      </div>

      <nav class="nav">
        <div v-for="group in navGroups" :key="group.name" class="nav-group">
          <p v-if="!collapsed" class="nav-label">{{ group.name }}</p>
          <el-menu :default-active="route.path" :collapse="collapsed" router class="menu">
            <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path">
              <el-icon><component :is="item.icon" /></el-icon>
              <template #title>{{ item.title }}</template>
            </el-menu-item>
          </el-menu>
        </div>
      </nav>

      <div class="sidebar-foot">
        <button class="collapse" type="button" :aria-label="collapsed ? '展开侧栏' : '收起侧栏'" @click="collapsed = !collapsed">
          <el-icon><component :is="collapsed ? 'Expand' : 'Fold'" /></el-icon>
        </button>
        <span v-if="!collapsed" class="version mono">v5.1.0</span>
      </div>
    </aside>

    <div class="content">
      <header class="topbar">
        <div class="topbar-left">
          <span class="context-label">实例</span>
          <el-select
            :model-value="instanceStore.selectedId"
            class="instance-select"
            placeholder="选择实例"
            @change="onInstanceChange"
          >
            <el-option
              v-for="item in instanceStore.list"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            >
              <span class="option">
                <span class="dot" :class="item.status.toLowerCase()"></span>
                <span>{{ item.name }}</span>
                <span class="dim mono">{{ item.host }}:{{ item.port }}</span>
              </span>
            </el-option>
          </el-select>
        </div>

        <div class="topbar-right">
          <span class="health">
            <span class="dot up"></span>
            {{ instanceStore.upCount }} / {{ instanceStore.list.length }} 在线
          </span>
          <span class="sep"></span>
          <el-dropdown trigger="click" @command="handleCommand">
            <button class="account" type="button">
              <span class="avatar">{{ auth.displayName.slice(0, 1).toUpperCase() }}</span>
              <span class="who">
                <span class="name">{{ auth.displayName }}</span>
                <span class="role">{{ auth.user?.role }}</span>
              </span>
              <el-icon class="chev"><CaretBottom /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="password">修改口令</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="main">
        <router-view />
      </main>
    </div>

    <el-dialog v-model="passwordVisible" title="修改口令" width="420px">
      <el-form label-width="88px" @submit.prevent>
        <el-form-item label="原口令">
          <el-input v-model="passwordForm.oldPassword" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-form-item label="新口令">
          <el-input v-model="passwordForm.newPassword" type="password" show-password autocomplete="new-password" placeholder="至少 6 位" />
        </el-form-item>
        <el-form-item label="确认新口令">
          <el-input v-model="passwordForm.confirm" type="password" show-password autocomplete="new-password" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordVisible = false">取消</el-button>
        <el-button type="primary" :loading="passwordLoading" @click="submitPassword">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.shell {
  display: grid;
  grid-template-columns: 232px minmax(0, 1fr);
  height: 100vh;
  background: var(--bg);
}

.shell.collapsed {
  grid-template-columns: 64px minmax(0, 1fr);
}

/* ---------- 侧栏 ---------- */

.sidebar {
  display: flex;
  flex-direction: column;
  background: var(--bg-subtle);
  border-right: 1px solid var(--line);
  min-height: 0;
}

.brand {
  display: flex;
  align-items: center;
  gap: 9px;
  height: 52px;
  padding: 0 14px;
  border-bottom: 1px solid var(--line);
  flex: none;
}

.mark {
  width: 20px;
  height: 20px;
  color: var(--accent);
  flex: none;
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.15;
  overflow: hidden;
}

.word {
  font-size: 13px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--text);
  white-space: nowrap;
}

.sub {
  font-size: 10.5px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--text-3);
}

.nav {
  flex: 1;
  overflow-y: auto;
  padding: 10px 0 8px;
}

.nav-group + .nav-group {
  margin-top: 14px;
}

.nav-label {
  margin: 0 0 4px;
  padding: 0 16px;
  font-size: 10.5px;
  font-weight: 500;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--text-3);
}

.menu {
  padding: 0;
}

.sidebar-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 44px;
  padding: 0 12px;
  border-top: 1px solid var(--line);
  flex: none;
}

.collapse {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  padding: 0;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-3);
  cursor: pointer;
  transition: background-color 0.14s var(--ease), color 0.14s var(--ease);
}

.collapse:hover {
  background: var(--surface-2);
  color: var(--text);
}

.version {
  font-size: 11px;
  color: var(--text-3);
}

/* ---------- 主区 ---------- */

.content {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 52px;
  padding: 0 20px 0 26px;
  border-bottom: 1px solid var(--line);
  background: var(--bg);
  flex: none;
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: none;
}

.context-label {
  font-size: 11.5px;
  letter-spacing: 0.05em;
  text-transform: uppercase;
  color: var(--text-3);
}

.sep {
  width: 1px;
  height: 18px;
  background: var(--line);
}

.instance-select {
  width: 190px;
}

.instance-select :deep(.el-select__wrapper) {
  height: 28px;
  min-height: 28px;
  background: transparent;
  box-shadow: none;
}

.instance-select :deep(.el-select__wrapper:hover) {
  background: var(--surface-2);
  box-shadow: none;
}

.option {
  display: flex;
  align-items: center;
  gap: 8px;
}

.health {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-2);
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  display: inline-block;
  flex: none;
}

.dot.up {
  background: var(--success);
  box-shadow: 0 0 0 3px rgba(63, 185, 80, 0.12);
}

.dot.down {
  background: var(--danger);
  box-shadow: 0 0 0 3px rgba(240, 84, 79, 0.12);
}

.dot.unknown {
  background: var(--text-3);
}

.account {
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: 186px;
  padding: 3px 5px 3px 3px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text);
  cursor: pointer;
  transition: background-color 0.14s var(--ease), border-color 0.14s var(--ease);
}

.account:hover {
  background: var(--surface-2);
  border-color: var(--line);
}

.avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 6px;
  background: var(--surface-3);
  border: 1px solid var(--line-strong);
  font-size: 11.5px;
  font-weight: 600;
  color: var(--text);
}

.who {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.2;
}

.name {
  font-size: 12.5px;
  font-weight: 500;
  max-width: 96px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.role {
  font-size: 10.5px;
  letter-spacing: 0.04em;
  color: var(--text-3);
}

.chev {
  font-size: 12px;
  color: var(--text-3);
}

.main {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  background: var(--bg);
}
</style>
