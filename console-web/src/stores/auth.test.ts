import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const loginMock = vi.fn()
const meMock = vi.fn()
const logoutMock = vi.fn()

vi.mock('@/api', () => ({
  authApi: {
    login: (...args: unknown[]) => loginMock(...args),
    me: (...args: unknown[]) => meMock(...args),
    logout: (...args: unknown[]) => logoutMock(...args)
  }
}))

import { TOKEN_KEY, USER_KEY } from '@/api/http'
import { useAuthStore } from './auth'

function createMemoryStorage() {
  const data = new Map<string, string>()
  return {
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => {
      data.set(key, String(value))
    },
    removeItem: (key: string) => {
      data.delete(key)
    },
    clear: () => {
      data.clear()
    }
  }
}

describe('useAuthStore', () => {
  let storage: ReturnType<typeof createMemoryStorage>

  beforeEach(() => {
    storage = createMemoryStorage()
    vi.stubGlobal('localStorage', storage)
    setActivePinia(createPinia())
    loginMock.mockReset()
    meMock.mockReset()
    logoutMock.mockReset()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('persists token and user on login and computes RBAC getters accurately', async () => {
    loginMock.mockResolvedValue({
      token: 'jwt-admin-token',
      user: { id: 1, username: 'admin', nickname: '系统管理员', role: 'ADMIN', status: 1 }
    })

    const store = useAuthStore()
    expect(store.isLoggedIn).toBe(false)
    expect(store.displayName).toBe('未登录')

    await store.login('admin', 'admin123')

    expect(store.isLoggedIn).toBe(true)
    expect(store.isAdmin).toBe(true)
    expect(store.canOperate).toBe(true)
    expect(store.displayName).toBe('系统管理员')
    expect(storage.getItem(TOKEN_KEY)).toBe('jwt-admin-token')
    expect(JSON.parse(storage.getItem(USER_KEY) || '{}').role).toBe('ADMIN')
  })

  it('distinguishes OPS and VIEWER permissions and handles corrupt localStorage gracefully', () => {
    storage.setItem(TOKEN_KEY, 'tok-ops')
    storage.setItem(USER_KEY, JSON.stringify({ id: 2, username: 'ops1', role: 'OPS', status: 1 }))
    setActivePinia(createPinia())
    const opsStore = useAuthStore()
    expect(opsStore.isAdmin).toBe(false)
    expect(opsStore.canOperate).toBe(true)
    expect(opsStore.displayName).toBe('ops1')

    storage.setItem(USER_KEY, JSON.stringify({ id: 3, username: 'viewer1', role: 'VIEWER', status: 1 }))
    setActivePinia(createPinia())
    const viewerStore = useAuthStore()
    expect(viewerStore.isAdmin).toBe(false)
    expect(viewerStore.canOperate).toBe(false)

    storage.setItem(USER_KEY, '{corrupt-json')
    setActivePinia(createPinia())
    const corruptStore = useAuthStore()
    expect(corruptStore.user).toBeNull()
  })

  it('refreshes current user and logs out on failure or explicit logout', async () => {
    storage.setItem(TOKEN_KEY, 'tok-valid')
    setActivePinia(createPinia())
    const store = useAuthStore()

    meMock.mockResolvedValueOnce({ id: 1, username: 'admin', role: 'ADMIN', status: 1 })
    await store.refresh()
    expect(store.user?.username).toBe('admin')

    meMock.mockRejectedValueOnce(new Error('401'))
    await store.refresh()
    expect(store.isLoggedIn).toBe(false)
    expect(store.user).toBeNull()
    expect(storage.getItem(TOKEN_KEY)).toBeNull()
    expect(storage.getItem(USER_KEY)).toBeNull()
  })

  it('calls server logout endpoint and clears local session even on error', async () => {
    storage.setItem(TOKEN_KEY, 'tok-active')
    storage.setItem(USER_KEY, JSON.stringify({ id: 1, username: 'admin', role: 'ADMIN', status: 1 }))
    setActivePinia(createPinia())
    const store = useAuthStore()

    logoutMock.mockResolvedValueOnce(undefined)
    await store.logoutAndRevoke()
    expect(logoutMock).toHaveBeenCalledTimes(1)
    expect(store.isLoggedIn).toBe(false)
    expect(storage.getItem(TOKEN_KEY)).toBeNull()

    // 即使服务端返回错误也应完成本地清理
    storage.setItem(TOKEN_KEY, 'tok-expired')
    setActivePinia(createPinia())
    const store2 = useAuthStore()
    logoutMock.mockRejectedValueOnce(new Error('401'))
    await store2.logoutAndRevoke()
    expect(logoutMock).toHaveBeenCalledTimes(2)
    expect(store2.isLoggedIn).toBe(false)
    expect(storage.getItem(TOKEN_KEY)).toBeNull()
  })
})
