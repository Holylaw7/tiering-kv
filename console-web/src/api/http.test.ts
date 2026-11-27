import { beforeEach, describe, expect, it, vi } from 'vitest'

const { warningSpy, errorSpy, logoutSpy } = vi.hoisted(() => ({
  warningSpy: vi.fn(),
  errorSpy: vi.fn(),
  logoutSpy: vi.fn()
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    warning: warningSpy,
    error: errorSpy
  }
}))

vi.mock('../stores/auth', () => ({
  useAuthStore: () => ({
    logout: logoutSpy
  })
}))

import http, { resetUnauthorizedGuard } from './http'

describe('http 401 unauthorized storm debounce', () => {
  beforeEach(() => {
    warningSpy.mockClear()
    errorSpy.mockClear()
    logoutSpy.mockClear()
    resetUnauthorizedGuard()
    vi.stubGlobal('location', { hash: '#/dashboard' })
    const storage = new Map<string, string>()
    vi.stubGlobal('localStorage', {
      getItem: (k: string) => storage.get(k) ?? null,
      setItem: (k: string, v: string) => storage.set(k, v),
      removeItem: (k: string) => storage.delete(k)
    })
  })

  it('deduplicates concurrent 401 responses into a single logout and warning toast', async () => {
    const adapter = () =>
      Promise.reject({
        response: { status: 401, data: { message: '未授权' } },
        message: 'Request failed with status code 401'
      })

    const results = await Promise.allSettled([
      http.get('/dashboard', { adapter }),
      http.get('/alerts/events', { adapter }),
      http.get('/instances', { adapter })
    ])

    expect(results.every((r) => r.status === 'rejected')).toBe(true)
    expect(logoutSpy).toHaveBeenCalledTimes(1)
    expect(warningSpy).toHaveBeenCalledTimes(1)
    expect(location.hash).toBe('#/login')
  })
})
