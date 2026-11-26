import http from './http'
import type {
  AlertChannelView, AlertEvent, AlertRule, BackupRecord,
  BackupScheduleRequest, BackupScheduleRunResult, BackupScheduleView,
  CheckpointResult, CheckpointStatusView,
  InstanceView, JsonKeysView, JsonQueryView, KeyDetail, KeyScanPage,
  LoginResponse, OpLog, OverviewView, PageResult, SlowLogView,
  SstableTableView, SystemInfo, TerminalHistory,
  TerminalResult, TimeSeriesKeysView, TimeSeriesPoint, TimeSeriesRangeView,
  TrendPoint, UserView, VectorCollectionsView, VectorSearchResultView, WebhookTestResult
} from './types'

export const authApi = {
  login: (username: string, password: string) =>
    http.post<unknown, LoginResponse>('/auth/login', { username, password }),
  me: () => http.get<unknown, UserView>('/auth/me'),
  changePassword: (oldPassword: string, newPassword: string) =>
    http.post<unknown, void>('/auth/password', { oldPassword, newPassword }),
  logout: () => http.post<unknown, void>('/auth/logout')
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
    http.delete<unknown, void>('/keys', { params: { instanceId, key } }),
  putHashField: (instanceId: number, key: string, field: string, value: string) =>
    http.put<unknown, void>('/keys/hash-field', { key, field, value }, { params: { instanceId } }),
  removeHashField: (instanceId: number, key: string, field: string) =>
    http.delete<unknown, void>('/keys/hash-field', { params: { instanceId, key, field } })
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
  resolve: (id: number) => http.post<unknown, void>(`/alerts/events/${id}/resolve`),
  channels: () => http.get<unknown, AlertChannelView[]>('/alerts/channels'),
  createChannel: (payload: Record<string, unknown>) =>
    http.post<unknown, AlertChannelView>('/alerts/channels', payload),
  updateChannel: (id: number, payload: Record<string, unknown>) =>
    http.put<unknown, AlertChannelView>(`/alerts/channels/${id}`, payload),
  removeChannel: (id: number) => http.delete<unknown, void>(`/alerts/channels/${id}`),
  testChannel: (id: number) => http.post<unknown, WebhookTestResult>(`/alerts/channels/${id}/test`)
}

export const backupApi = {
  list: (instanceId?: number) =>
    http.get<unknown, BackupRecord[]>('/backups', { params: { instanceId } }),
  create: (instanceId: number) =>
    http.post<unknown, BackupRecord>('/backups', null, { params: { instanceId } }),
  restore: (id: number) => http.post<unknown, BackupRecord>(`/backups/${id}/restore`),
  remove: (id: number) => http.delete<unknown, void>(`/backups/${id}`),
  checkpoint: (instanceId: number) =>
    http.post<unknown, CheckpointResult>('/backups/checkpoint', null, { params: { instanceId } }),
  checkpointStatus: (instanceId: number) =>
    http.get<unknown, CheckpointStatusView>('/backups/checkpoint/status', { params: { instanceId } }),
  schedules: (instanceId?: number) =>
    http.get<unknown, BackupScheduleView[]>('/backups/schedules', { params: { instanceId } }),
  createSchedule: (payload: BackupScheduleRequest) =>
    http.post<unknown, BackupScheduleView>('/backups/schedules', payload),
  updateSchedule: (id: number, payload: BackupScheduleRequest) =>
    http.put<unknown, BackupScheduleView>(`/backups/schedules/${id}`, payload),
  removeSchedule: (id: number) =>
    http.delete<unknown, void>(`/backups/schedules/${id}`),
  runScheduleNow: (id: number) =>
    http.post<unknown, BackupScheduleRunResult>(`/backups/schedules/${id}/run-now`)
}

export interface AuditQuery {
  action?: string
  username?: string
  success?: boolean
  from?: string
  to?: string
  page?: number
  size?: number
}

export const systemApi = {
  info: (instanceId: number) =>
    http.get<unknown, SystemInfo>('/system/info', { params: { instanceId } }),
  /** 冷层 SSTable 清单（按需读取，不走全量 INFO） */
  tables: (instanceId: number) =>
    http.get<unknown, SstableTableView>('/system/tables', { params: { instanceId } })
}

export const auditApi = {
  page: (params: AuditQuery) => http.get<unknown, PageResult<OpLog>>('/audit', { params }),
  /** 导出 CSV（返回 Blob，由调用方触发下载）。 */
  exportCsv: (params: AuditQuery) =>
    http.get<unknown, Blob>('/audit/export', { params, responseType: 'blob' })
}

export const slowLogApi = {
  list: (instanceId: number, limit = 50) =>
    http.get<unknown, SlowLogView>('/slowlog', { params: { instanceId, limit } }),
  reset: (instanceId: number) =>
    http.post<unknown, void>('/slowlog/reset', null, { params: { instanceId } })
}

export const multiModelApi = {
  vectorCollections: (instanceId: number) =>
    http.get<unknown, VectorCollectionsView>('/multimodel/vector/collections', { params: { instanceId } }),
  addVector: (payload: { instanceId: number; collection?: string; id: string; values: number[] }) =>
    http.post<unknown, void>('/multimodel/vector/add', payload),
  searchVector: (payload: { instanceId: number; collection?: string; vector: number[]; topK?: number }) =>
    http.post<unknown, VectorSearchResultView>('/multimodel/vector/search', payload),
  deleteVector: (instanceId: number, collection: string | undefined, id: string) =>
    http.delete<unknown, boolean>('/multimodel/vector/item', { params: { instanceId, collection, id } }),
  dropVectorCollection: (instanceId: number, collection: string) =>
    http.delete<unknown, boolean>('/multimodel/vector/collection', { params: { instanceId, collection } }),
  checkpointVector: (instanceId: number, collection?: string) =>
    http.post<unknown, string>('/multimodel/vector/checkpoint', null, { params: { instanceId, collection } }),

  timeSeriesKeys: (instanceId: number) =>
    http.get<unknown, TimeSeriesKeysView>('/multimodel/timeseries/keys', { params: { instanceId } }),
  addTimeSeriesPoint: (payload: { instanceId: number; key: string; timestamp?: number; value: number }) =>
    http.post<unknown, TimeSeriesPoint>('/multimodel/timeseries/add', payload),
  incrTimeSeries: (payload: { instanceId: number; key: string; delta: number; timestamp?: number }) =>
    http.post<unknown, number>('/multimodel/timeseries/incrby', payload),
  queryTimeSeriesRange: (params: {
    instanceId: number
    key: string
    from?: string
    to?: string
    aggregation?: string
    bucketMs?: number
    count?: number
  }) => http.get<unknown, TimeSeriesRangeView>('/multimodel/timeseries/range', { params }),

  jsonKeys: (instanceId: number, pattern = '*', limit = 100) =>
    http.get<unknown, JsonKeysView>('/multimodel/json/keys', { params: { instanceId, pattern, limit } }),
  getJson: (instanceId: number, key: string, path = '$') =>
    http.get<unknown, JsonQueryView>('/multimodel/json/get', { params: { instanceId, key, path } }),
  setJson: (payload: { instanceId: number; key: string; path?: string; value: string }) =>
    http.put<unknown, void>('/multimodel/json/set', payload),
  deleteJson: (instanceId: number, key: string, path = '$') =>
    http.delete<unknown, number>('/multimodel/json/del', { params: { instanceId, key, path } }),
  numIncrJson: (payload: { instanceId: number; key: string; path: string; delta: number }) =>
    http.post<unknown, string>('/multimodel/json/numincrby', payload),
  arrAppendJson: (payload: { instanceId: number; key: string; path?: string; value: string }) =>
    http.post<unknown, number>('/multimodel/json/arrappend', payload)
}