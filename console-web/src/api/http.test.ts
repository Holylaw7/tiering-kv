import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AxiosAdapter, InternalAxiosRequestConfig } from 'axios'

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

import http, { resetUnauthorizedGuard, TOKEN_KEY, USER_KEY } from './http'

describe('http interceptors & 401 unauthorized storm debounce', () => {
  let storage: Map<string, string>

  beforeEach(() => {
    warningSpy.mockClear()
    errorSpy.mockClear()
    logoutSpy.mockReset()
    resetUnauthorizedGuard()
    vi.useFakeTimers()
    vi.stubGlobal('location', { hash: '#/dashboard' })
    storage = new Map<string, string>()
    vi.stubGlobal('localStorage', {
      getItem: (k: string) => storage.get(k) ?? null,
      setItem: (k: string, v: string) => storage.set(k, v),
      removeItem: (k: string) => storage.delete(k)
    })
  })

  afterEach(() => {
    resetUnauthorizedGuard()
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('attaches Bearer token from localStorage and unwraps ApiResponse envelope', async () => {
    storage.set(TOKEN_KEY, 'jwt-abc')
    let capturedAuth: unknown
    const adapter: AxiosAdapter = async (config: InternalAxiosRequestConfig) => {
      capturedAuth = config.headers?.Authorization
      return {
        data: { code: 0, message: 'ok', data: { healthy: true } },
        status: 200,
        statusText: 'OK',
        headers: {},
        config
      }
    }

    const res = await http.get('/health', { adapter })
    expect(capturedAuth).toBe('Bearer jwt-abc')
    expect(res).toEqual({ healthy: true })
  })

  it('rejects and shows error toast when envelope code is non-zero, and passes raw payloads through', async () => {
    const errAdapter: AxiosAdapter = async (config) => ({
      data: { code: 40001, message: '参数非法', data: null },
      status: 200,
      statusText: 'OK',
      headers: {},
      config
    })

    await expect(http.get('/bad', { adapter: errAdapter })).rejects.toThrow('参数非法')
    expect(errorSpy).toHaveBeenCalledWith('参数非法')

    const rawAdapter: AxiosAdapter = async (config) => ({
      data: 'csv,content',
      status: 200,
      statusText: 'OK',
      headers: {},
      config
    })
    const raw = await http.get('/export', { adapter: rawAdapter })
    expect(raw).toBe('csv,content')
  })

  it('deduplicates concurrent 401 responses into a single logout and warning toast, then resets after cooldown', async () => {
    const adapter: AxiosAdapter = () =>
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

    // After 1500ms cooldown, a subsequent 401 triggers cleanly again
    vi.advanceTimersByTime(1600)
    await expect(http.get('/instances', { adapter })).rejects.toBeDefined()
    expect(logoutSpy).toHaveBeenCalledTimes(2)
    expect(warningSpy).toHaveBeenCalledTimes(2)
  })

  it('falls back to clearing localStorage if auth store logout throws, and shows ElMessage.error for non-401 errors', async () => {
    storage.set(TOKEN_KEY, 'stale-token')
    storage.set(USER_KEY, '{"username":"ops"}')
    logoutSpy.mockImplementationOnce(() => {
      throw new Error('pinia not ready')
    })

    const unauthAdapter: AxiosAdapter = () =>
      Promise.reject({
        response: { status: 401, data: {} },
        message: '401'
      })
    await expect(http.get('/protected', { adapter: unauthAdapter })).rejects.toBeDefined()
    expect(storage.has(TOKEN_KEY)).toBe(false)
    expect(storage.has(USER_KEY)).toBe(false)

    const serverErrAdapter: AxiosAdapter = () =>
      Promise.reject({
        response: { status: 500, data: { message: '内部服务异常' } },
        message: 'Request failed'
      })
    await expect(http.get('/boom', { adapter: serverErrAdapter })).rejects.toBeDefined()
    expect(errorSpy).toHaveBeenCalledWith('内部服务异常')
  })
})
