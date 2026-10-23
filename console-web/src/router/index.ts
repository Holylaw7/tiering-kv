import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true, title: '登录' }
    },
    {
      path: '/',
      component: () => import('@/layouts/DefaultLayout.vue'),
      redirect: '/dashboard',
      children: [
        { path: 'dashboard', name: 'dashboard', component: () => import('@/views/DashboardView.vue'), meta: { title: '运行总览', icon: 'Odometer' } },
        { path: 'instances', name: 'instances', component: () => import('@/views/InstancesView.vue'), meta: { title: '实例管理', icon: 'Coin' } },
        { path: 'keyspace', name: 'keyspace', component: () => import('@/views/KeyspaceView.vue'), meta: { title: '键空间', icon: 'Grid' } },
        { path: 'terminal', name: 'terminal', component: () => import('@/views/TerminalView.vue'), meta: { title: '在线终端', icon: 'Monitor' } },
        { path: 'alerts', name: 'alerts', component: () => import('@/views/AlertsView.vue'), meta: { title: '告警中心', icon: 'Bell' } },
        { path: 'backups', name: 'backups', component: () => import('@/views/BackupsView.vue'), meta: { title: '备份恢复', icon: 'Files' } },
        { path: 'audit', name: 'audit', component: () => import('@/views/AuditView.vue'), meta: { title: '审计日志', icon: 'Document' } },
        { path: 'users', name: 'users', component: () => import('@/views/UsersView.vue'), meta: { title: '用户管理', icon: 'User', adminOnly: true } }
      ]
    },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
  ]
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.adminOnly && !auth.isAdmin) {
    return { path: '/dashboard' }
  }
  return true
})

export default router
