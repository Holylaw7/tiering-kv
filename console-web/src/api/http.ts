import axios, { type AxiosInstance } from 'axios'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'

export const TOKEN_KEY = 'tk_console_token'
export const USER_KEY = 'tk_console_user'

const UNAUTHORIZED_COOLDOWN_MS = 1500
let handlingUnauthorized = false
let unauthorizedTimer: ReturnType<typeof setTimeout> | null = null

/** 测试辅助：重置 401 防重弹窗锁。 */
export function resetUnauthorizedGuard() {
  handlingUnauthorized = false
  if (unauthorizedTimer !== null) {
    clearTimeout(unauthorizedTimer)
    unauthorizedTimer = null
  }
}

const http: AxiosInstance = axios.create({
  baseURL: '/api/v1',
  timeout: 20000
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers = config.headers ?? {}
    config.headers.Authorization = 'Bearer ' + token
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && typeof body === 'object' && 'code' in body && 'data' in body) {
      if (body.code !== 0) {
        ElMessage.error(body.message || '请求失败')
        return Promise.reject(new Error(body.message || '请求失败'))
      }
      return body.data
    }
    return body
  },
  (error) => {
    const status = error?.response?.status
    const message = error?.response?.data?.message || error.message || '网络异常'
    if (status === 401) {
      if (!handlingUnauthorized) {
        handlingUnauthorized = true
        try {
          useAuthStore().logout()
        } catch {
          localStorage.removeItem(TOKEN_KEY)
          localStorage.removeItem(USER_KEY)
        }
        if (location.hash !== '#/login') {
          location.hash = '#/login'
        }
        ElMessage.warning('登录已过期，请重新登录')
        unauthorizedTimer = setTimeout(() => {
          handlingUnauthorized = false
          unauthorizedTimer = null
        }, UNAUTHORIZED_COOLDOWN_MS)
      }
    } else {
      ElMessage.error(message)
    }
    return Promise.reject(error)
  }
)

export default http
