export type Role = 'ADMIN' | 'OPS' | 'VIEWER'

export interface UserView {
  id: number
  username: string
  nickname?: string
  email?: string
  role: Role
  status: number
  lastLoginAt?: string
  createdAt?: string
}

export interface LoginResponse {
  token: string
  expiresInSeconds: number
  user: UserView
}

export type InstanceStatus = 'UP' | 'DOWN' | 'UNKNOWN'

export interface InstanceView {
  id: number
  name: string
  host: string
  port: number
  status: InstanceStatus
  version?: string
  lastProbeAt?: string
  remark?: string
  enabled: boolean
  hasPassword: boolean
}

export interface LiveMetrics {
  version?: string
  uptimeSeconds: number
  usedMemory: number
  maxMemory: number
  usedMemoryJvm: number
  totalKeys: number
  connections: number
  activeRequests: number
  qps: number
  opsPerSec: number
  avgLatencyMs: number
  errors: number
  maxJvmHeap: number
}

export interface OverviewView {
  instances: InstanceView[]
  selected?: InstanceView
  live?: LiveMetrics
  totalKeys: number
  firingAlerts: number
  resolvedToday: number
  uptimeSeconds: number
}

export interface TrendPoint {
  time: string
  qps: number
  avgLatencyMs: number
  connections: number
  usedMemory: number
  totalKeys: number
  errors: number
  maxMemory: number
}

export interface KeyEntry {
  key: string
  type: string
  ttl: number
}

export interface KeyScanPage {
  cursor: string
  keys: KeyEntry[]
  finished: boolean
}

export interface KeyDetail {
  key: string
  type: string
  ttl: number
  value: unknown
}

export interface TerminalResult {
  replyType: string
  payload: unknown
  costMs: number
  error: boolean
}

export interface TerminalHistory {
  id: number
  username?: string
  instanceId: number
  command: string
  success: boolean
  costMs: number
  replyType?: string
  createdAt: string
}

export interface AlertRule {
  id: number
  name: string
  instanceId?: number
  metric: string
  operator: 'GT' | 'LT'
  threshold: number
  durationSeconds: number
  level: 'WARN' | 'CRIT'
  enabled: boolean
  createdAt?: string
}

export interface AlertEvent {
  id: number
  ruleId: number
  instanceId: number
  ruleName: string
  metric: string
  value: number
  threshold: number
  level: 'WARN' | 'CRIT'
  message: string
  status: 'FIRING' | 'RESOLVED'
  firedAt: string
  resolvedAt?: string
}

export interface BackupRecord {
  id: number
  instanceId: number
  fileName: string
  sizeBytes: number
  keyCount: number
  status: 'RUNNING' | 'SUCCESS' | 'FAILED'
  message?: string
  createdAt: string
  finishedAt?: string
}

export interface OpLog {
  id: number
  userId?: number
  username?: string
  action: string
  target?: string
  detail?: string
  ip?: string
  success: boolean
  createdAt: string
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}
