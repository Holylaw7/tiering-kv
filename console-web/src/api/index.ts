import http from './http'
import type {
  AlertEvent, AlertRule, BackupRecord, InstanceView, KeyDetail, KeyScanPage,
  LoginResponse, OpLog, OverviewView, PageResult, TerminalHistory,
  TerminalResult, TrendPoint, UserView
} from './types'

export const authApi = {
  login: (username: string, password: string) =>
    http.post<unknown, LoginResponse>('/auth/login', { username, password }),
  me: () => http.get<unknown, UserView>('/auth/me')
}

export const userApi = {
  page: (params: { keyword?: string; page?: number; size?: number }) =>
    http.get<unknown, PageResult<UserView>>('/users', { params }),
  create: (payload: { username: string; password: string; nickname?: string; email?: string; role: string }) =>
    http.post<unknown, UserView>('/users', payload),
  update: (id: number, payload: Record<string, unknown>) =>
    http.put<unknown, UserView>(`/users/${id}`, payload),
  resetPassword: (id: number, password: string) =>
    http.put<unknown, void>(`/users/${id}/password`, { password }),
  remove: (id: number) => http.delete<unknown, void>(`/users/${id}`)
}

export const instanceApi = {
  list: () => http.get<unknown, InstanceView[]>('/instances'),
  create: (payload: Record<string, unknown>) =>
    http.post<unknown, InstanceView>('/instances', payload),
  update: (id: number, payload: Record<string, unknown>) =>
    http.put<unknown, InstanceView>(`/instances/${id}`, payload),
  remove: (id: number) => http.delete<unknown, void>(`/instances/${id}`),
  probe: (id: number) => http.post<unknown, InstanceView>(`/instances/${id}/probe`)
}

export const dashboardApi = {
  overview: (instanceId?: number) =>
    http.get<unknown, OverviewView>('/dashboard/overview', { params: { instanceId } }),
  trends: (instanceId: number, minutes = 30) =>
    http.get<unknown, TrendPoint[]>('/dashboard/trends', { params: { instanceId, minutes } })
}

export const keyspaceApi = {
  scan: (params: { instanceId: number; cursor?: string; pattern?: string; count?: number }) =>
    http.get<unknown, KeyScanPage>('/keys/scan', { params }),
  detail: (instanceId: number, key: string) =>
    http.get<unknown, KeyDetail>('/keys/detail', { params: { instanceId, key } }),
  put: (instanceId: number, key: string, value: string) =>
    http.put<unknown, void>('/keys', { key, value }, { params: { instanceId } }),
  ttl: (instanceId: number, key: string, ttlSeconds: number) =>
    http.put<unknown, void>('/keys/ttl', { key, ttlSeconds }, { params: { instanceId } }),
  remove: (instanceId: number, key: string) =>
    http.delete<unknown, void>('/keys', { params: { instanceId, key } })
}

export const terminalApi = {
  execute: (instanceId: number, command: string) =>
    http.post<unknown, TerminalResult>('/terminal/execute', { instanceId, command }),
  history: (page = 1, size = 20) =>
    http.get<unknown, PageResult<TerminalHistory>>('/terminal/history', { params: { page, size } })
}

export const alertApi = {
  rules: () => http.get<unknown, AlertRule[]>('/alerts/rules'),
  createRule: (payload: Record<string, unknown>) =>
    http.post<unknown, AlertRule>('/alerts/rules', payload),
  updateRule: (id: number, payload: Record<string, unknown>) =>
    http.put<unknown, AlertRule>(`/alerts/rules/${id}`, payload),
  removeRule: (id: number) => http.delete<unknown, void>(`/alerts/rules/${id}`),
  events: (params: { status?: string; page?: number; size?: number }) =>
    http.get<unknown, PageResult<AlertEvent>>('/alerts/events', { params }),
  resolve: (id: number) => http.post<unknown, void>(`/alerts/events/${id}/resolve`)
}

export const backupApi = {
  list: (instanceId?: number) =>
    http.get<unknown, BackupRecord[]>('/backups', { params: { instanceId } }),
  create: (instanceId: number) =>
    http.post<unknown, BackupRecord>('/backups', null, { params: { instanceId } }),
  restore: (id: number) => http.post<unknown, number>(`/backups/${id}/restore`),
  remove: (id: number) => http.delete<unknown, void>(`/backups/${id}`)
}

export const auditApi = {
  page: (params: { action?: string; page?: number; size?: number }) =>
    http.get<unknown, PageResult<OpLog>>('/audit', { params })
}
