<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useInstanceStore } from '@/stores/instance'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const instanceStore = useInstanceStore()
const collapsed = ref(false)

const menus = computed(() => {
  const root = router.getRoutes().find((item) => item.path === '/')
  const children = root?.children ?? []
  return children
    .filter((item) => !item.meta?.adminOnly || auth.isAdmin)
    .map((item) => ({
      path: '/' + item.path,
      title: String(item.meta?.title ?? ''),
      icon: String(item.meta?.icon ?? 'Menu')
    }))
})

const currentTitle = computed(() => String(route.meta?.title ?? ''))

onMounted(() => {
  instanceStore.load().catch(() => undefined)
})

async function handleCommand(command: string) {
  if (command === 'logout') {
    await ElMessageBox.confirm('确认退出登录？', '提示', { type: 'warning' })
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
  <el-container class="layout">
    <el-aside :width="collapsed ? '64px' : '218px'" class="aside">
      <div class="brand">
        <span class="brand-mark">⚡</span>
        <span v-if="!collapsed" class="brand-text">
          Tiering-KV
          <small>运维管理平台</small>
        </span>
      </div>
      <el-menu
        :default-active="route.path"
        :collapse="collapsed"
        router
        class="menu"
        background-color="transparent"
      >
        <el-menu-item v-for="menu in menus" :key="menu.path" :index="menu.path">
          <el-icon><component :is="menu.icon" /></el-icon>
          <template #title>{{ menu.title }}</template>
        </el-menu-item>
      </el-menu>
      <div class="aside-footer">
        <el-button text size="small" @click="collapsed = !collapsed">
          <el-icon><component :is="collapsed ? 'Expand' : 'Fold'" /></el-icon>
        </el-button>
      </div>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <span class="page-title">{{ currentTitle }}</span>
          <el-divider direction="vertical" />
          <el-select
            :model-value="instanceStore.selectedId"
            size="small"
            placeholder="选择实例"
            style="width: 220px"
            @change="onInstanceChange"
          >
            <el-option
              v-for="item in instanceStore.list"
              :key="item.id"
              :label="`${item.name}  (${item.host}:${item.port})`"
              :value="item.id"
            >
              <span class="dot" :class="item.status.toLowerCase()"></span>
              {{ item.name }}
              <span class="muted mono">{{ item.host }}:{{ item.port }}</span>
            </el-option>
          </el-select>
        </div>
        <div class="header-right">
          <el-tag type="success" effect="dark" round>
            在线实例 {{ instanceStore.upCount }} / {{ instanceStore.list.length }}
          </el-tag>
          <el-dropdown @command="handleCommand">
            <span class="user">
              <el-avatar :size="28">{{ auth.displayName.slice(0, 1) }}</el-avatar>
              <span class="user-name">{{ auth.displayName }}</span>
              <el-tag size="small" :type="auth.isAdmin ? 'danger' : 'info'">{{ auth.user?.role }}</el-tag>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
}

.aside {
  background: #121820;
  border-right: 1px solid var(--console-border);
  display: flex;
  flex-direction: column;
  transition: width 0.2s ease;
}

.brand {
  height: 60px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 18px;
  border-bottom: 1px solid var(--console-border);
}

.brand-mark {
  font-size: 20px;
}

.brand-text {
  font-weight: 700;
  font-size: 15px;
  line-height: 1.1;
  display: flex;
  flex-direction: column;
}

.brand-text small {
  font-weight: 400;
  font-size: 11px;
  color: var(--el-text-color-secondary);
  letter-spacing: 0.5px;
}

.menu {
  border-right: none;
  flex: 1;
  padding-top: 8px;
}

.aside-footer {
  padding: 8px 12px;
  border-top: 1px solid var(--console-border);
}

.header {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--console-border);
  background: #121820;
  padding: 0 20px;
}

.header-left,
.header-right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
}

.user-name {
  font-size: 13px;
}

.main {
  background: var(--console-bg);
  padding: 0;
  overflow-y: auto;
}

.muted {
  color: var(--el-text-color-secondary);
  font-size: 11px;
  margin-left: 8px;
}

.dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  margin-right: 6px;
}

.dot.up {
  background: #35c98b;
}

.dot.down {
  background: #f56c6c;
}

.dot.unknown {
  background: #909399;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
