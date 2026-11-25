<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Bell,
  CaretBottom,
  Coin,
  Document,
  Expand,
  Files,
  Fold,
  Grid,
  Menu,
  Monitor,
  Odometer,
  Operation,
  Setting,
  Timer,
  User
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useInstanceStore } from '@/stores/instance'
import { alertApi, authApi } from '@/api'
import StatusBadge from '@/components/StatusBadge.vue'

const NAV_ICONS: Record<string, Component> = {
  Odometer,
  Coin,
  Grid,
  Monitor,
  Bell,
  Timer,
  Files,
  Document,
  Setting,
  User,
  Menu
}

function iconOf(name: string): Component {
  return NAV_ICONS[name] ?? Menu
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const instanceStore = useInstanceStore()

const collapsed = ref(false)
const mobileDrawer = ref(false)
const firingAlertCount = ref(0)

const GROUP_ORDER = ['概览', '数据', '运维', '系统'] as const
const GROUP_OF: Record<string, string> = {
  '/dashboard': '概览',
  '/keyspace': '数据',
  '/terminal': '数据',
  '/instances': '运维',
  '/alerts': '运维',
  '/slowlog': '运维',
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
const currentGroup = computed(() => GROUP_OF[route.path] ?? '控制台')
const currentInstance = computed(() => instanceStore.selected)

async function loadAlertSummary() {
  try {
    const page = await alertApi.events({ status: 'FIRING', page: 1, size: 1 })
    firingAlertCount.value = page.total
  } catch {
    // 顶栏徽标为非阻塞辅助提示，网络或权限受限时静默忽略
  }
}

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
  await Promise.all([instanceStore.load(), loadAlertSummary()])
})

watch(
  () => route.path,
  () => {
    mobileDrawer.value = false
    loadAlertSummary()
    if (instanceStore.list.some((item) => item.status === 'UNKNOWN')) {
      instanceStore.load().catch(() => {})
    }
  }
)

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
}

function goAlerts() {
  router.push('/alerts')
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
          <span class="wordmark">Tiering-KV</span>
          <span class="brand-sub">Ops Console</span>
        </div>
      </div>

      <nav class="nav">
        <div v-for="group in navGroups" :key="group.name" class="nav-group">
          <p v-if="!collapsed" class="nav-label">{{ group.name }}</p>
          <el-menu :default-active="route.path" :collapse="collapsed" router class="menu">
            <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path">
              <el-icon><component :is="iconOf(item.icon)" /></el-icon>
              <template #title>{{ item.title }}</template>
            </el-menu-item>
          </el-menu>
        </div>
      </nav>

      <div class="sidebar-foot">
        <button class="collapse" type="button" :aria-label="collapsed ? '展开侧栏' : '收起侧栏'" @click="collapsed = !collapsed">
          <el-icon><component :is="collapsed ? Expand : Fold" /></el-icon>
        </button>
        <span v-if="!collapsed" class="version mono">v5.4.0</span>
      </div>
    </aside>

    <div class="content">
      <header class="topbar">
        <div class="topbar-left">
          <button
            class="mobile-trigger"
            type="button"
            aria-label="打开导航菜单"
            @click="mobileDrawer = true"
          >
            <el-icon><Operation /></el-icon>
          </button>
          <div class="breadcrumbs">
            <span class="crumb-group">{{ currentGroup }}</span>
            <span class="crumb-slash">/</span>
            <span class="crumb">{{ currentTitle }}</span>
          </div>
          <span class="sep"></span>
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
                <span class="option-name">{{ item.name }}</span>
                <span class="option-addr mono">{{ item.host }}:{{ item.port }}</span>
              </span>
            </el-option>
          </el-select>
          <span v-if="currentInstance" class="endpoint mono">
            {{ currentInstance.host }}:{{ currentInstance.port }}
          </span>
        </div>

        <div class="topbar-right">
          <button
            type="button"
            class="alert-pill"
            :class="{ 'is-firing': firingAlertCount > 0 }"
            @click="goAlerts"
          >
            <span class="dot" :class="firingAlertCount > 0 ? 'down' : 'up'"></span>
            <span>{{ firingAlertCount > 0 ? `${firingAlertCount} 条活跃告警` : '告警正常' }}</span>
          </button>
          <StatusBadge
            :label="currentInstance?.status === 'UP' ? '运行中' : currentInstance?.status === 'DOWN' ? '连接失败' : '未知'"
            :tone="currentInstance?.status === 'UP' ? 'success' : currentInstance?.status === 'DOWN' ? 'danger' : 'neutral'"
            dot
          />
          <span class="online">{{ instanceStore.upCount }}/{{ instanceStore.list.length }} 实例在线</span>
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

    <el-drawer v-if="mobileDrawer" v-model="mobileDrawer" direction="ltr" size="252px" title="导航菜单" class="mobile-nav-drawer">
      <div v-for="group in navGroups" :key="group.name" class="nav-group">
        <p class="nav-label">{{ group.name }}</p>
        <el-menu :default-active="route.path" router class="menu">
          <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path">
            <el-icon><component :is="iconOf(item.icon)" /></el-icon>
            <template #title>{{ item.title }}</template>
          </el-menu-item>
        </el-menu>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.shell {
  display: grid;
  grid-template-columns: 236px minmax(0, 1fr);
  height: 100vh;
  background: var(--bg);
}

.shell.collapsed {
  grid-template-columns: 60px minmax(0, 1fr);
}

/* ---------- 侧栏 ---------- */

.sidebar {
  display: flex;
  flex-direction: column;
  background: var(--surface-2);
  border-right: 1px solid var(--line-soft);
  min-height: 0;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 54px;
  padding: 0 16px;
  border-bottom: 1px solid var(--line-soft);
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
}

.wordmark {
  font-size: var(--text-base);
  font-weight: 600;
  letter-spacing: -0.015em;
  color: var(--text);
  white-space: nowrap;
}

.brand-sub {
  font-size: 10.5px;
  color: var(--text-3);
  letter-spacing: 0.03em;
}

.nav {
  flex: 1;
  overflow-y: auto;
  padding: 6px 0 10px;
}

.nav-group + .nav-group {
  margin-top: 16px;
}

.nav-label {
  margin: 0 0 2px;
  padding: 0 18px;
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.07em;
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
  height: 40px;
  padding: 0 12px;
  border-top: 1px solid var(--line-soft);
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
  background: var(--surface-3);
  color: var(--text-1);
}

.version {
  font-size: 10.5px;
  color: var(--text-3);
}

/* ---------- 顶栏 ---------- */

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
  gap: 16px;
  height: 52px;
  padding: 0 24px;
  background: var(--surface);
  border-bottom: 1px solid var(--line-soft);
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
  gap: 14px;
  flex: none;
}

.breadcrumbs {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}

.crumb-group {
  font-size: var(--text-sm);
  color: var(--text-3);
}

.crumb-slash {
  font-size: var(--text-xs);
  color: var(--line-strong);
}

.crumb {
  font-size: var(--text-base);
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--text);
}

.mobile-trigger {
  display: none;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--surface);
  color: var(--text-2);
  cursor: pointer;
}

.alert-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 24px;
  padding: 0 9px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface-2);
  color: var(--text-2);
  font-size: var(--text-xs);
  cursor: pointer;
  transition: border-color 0.14s var(--ease), background-color 0.14s var(--ease);
}

.alert-pill:hover {
  border-color: var(--line-strong);
  background: var(--surface-3);
}

.alert-pill.is-firing {
  background: var(--danger-soft);
  border-color: var(--danger-line);
  color: var(--danger);
  font-weight: 500;
}

.sep {
  width: 1px;
  height: 16px;
  background: var(--line);
}

.instance-select {
  width: 172px;
}

.instance-select :deep(.el-select__wrapper) {
  height: 28px;
  min-height: 28px;
  background: transparent;
  box-shadow: none;
  padding-left: 4px;
}

.instance-select :deep(.el-select__wrapper:hover) {
  background: var(--surface-3);
  box-shadow: none;
}

.option {
  display: flex;
  align-items: center;
  gap: 8px;
}

.option-name {
  color: var(--text-1);
}

.option-addr {
  color: var(--text-3);
  font-size: 11px;
}

.endpoint {
  font-size: 11.5px;
  color: var(--text-3);
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  display: inline-block;
  flex: none;
  background: var(--text-3);
}

.dot.up {
  background: var(--success);
}

.dot.down {
  background: var(--danger);
}

.online {
  font-size: var(--text-sm);
  color: var(--text-3);
}

.account {
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: 190px;
  padding: 3px 6px 3px 3px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-1);
  cursor: pointer;
  transition: background-color 0.14s var(--ease), border-color 0.14s var(--ease);
}

.account:hover {
  background: var(--surface-3);
}

.avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 6px;
  background: var(--surface-3);
  border: 1px solid var(--line);
  font-size: 11px;
  font-weight: 600;
  color: var(--text-1);
}

.who {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.2;
}

.name {
  font-size: var(--text-sm);
  font-weight: 500;
  max-width: 96px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.role {
  font-size: 10px;
  letter-spacing: 0.05em;
  color: var(--text-3);
}

.chev {
  font-size: 11px;
  color: var(--text-3);
}

@media (max-width: 1380px) {
  .endpoint {
    display: none;
  }
}

@media (max-width: 960px) {
  .shell,
  .shell.collapsed {
    grid-template-columns: minmax(0, 1fr);
  }

  .sidebar {
    display: none;
  }

  .mobile-trigger {
    display: inline-flex;
  }

  .online,
  .crumb-group,
  .crumb-slash,
  .crumb,
  .sep {
    display: none;
  }

  .instance-select {
    width: 132px;
  }

  .topbar {
    padding: 0 14px;
    gap: 8px;
  }
}

@media (max-width: 640px) {
  .alert-pill,
  .who {
    display: none;
  }

  .topbar-right {
    gap: 8px;
  }
}

.main {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  background: var(--bg);
}
</style>