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
  tlsEnabled?: boolean
  tlsVerifyHostname?: boolean
  hasTlsCaCert?: boolean
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
  /* 存储分层（内核 INFO # Tiering，TD-095） */
  memtableUsedBytes: number
  memtableMaxBytes: number
  memtableEntries: number
  hotKeys: number
  coldEntries: number
  hotKeyShare: number
  coldSstableCount: number
  coldDiskUsageBytes: number
  coldShare: number
  flushCount: number
  flushBytes: number
  migrationPending: number
  migrationSuccess: number
  migrationFailed: number
  cacheHits: number
  cacheMisses: number
  cacheHitRate: number
  cacheEvictions: number
  p50Millis: number
  p95Millis: number
  p99Millis: number
  latencyMaxMillis: number
  latencySamples: number
  tieringReported: boolean
  flushInProgress: number
  compactionInProgress: number
  compactionCount: number
  compactionInputBytes: number
  compactionOutputBytes: number
  compactionLatencyMs: number
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

export interface AnalyzeKeyspaceRequest {
  pattern?: string
  maxKeys?: number
  delimiter?: string
  topN?: number
}

export interface PrefixStat {
  prefix: string
  keyCount: number
  totalSizeUnits: number
  sharePercent: number
}

export interface BigKeyEntry {
  key: string
  type: string
  sizeUnits: number
  unitLabel: string
  ttl: number
}

export interface TtlDistribution {
  noExpire: number
  under1h: number
  between1hAnd24h: number
  over24h: number
}

export interface KeyspaceAnalysisView {
  instanceId: number
  pattern: string
  delimiter: string
  scannedKeys: number
  totalDbSize: number
  truncated: boolean
  durationMs: number
  typeDistribution: Record<string, number>
  ttlDistribution: TtlDistribution
  topPrefixes: PrefixStat[]
  bigKeys: BigKeyEntry[]
  recommendations: string[]
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

export interface AlertChannelView {
  id: number
  name: string
  type: string
  url: string
  hasSecret: boolean
  silenceSeconds: number
  enabled: boolean
  createdAt?: string
  updatedAt?: string
}

export interface WebhookTestResult {
  delivered: boolean
  statusCode: number
  message: string
  costMs: number
}

export type RestoreStatus = 'NONE' | 'RESTORING' | 'SUCCEEDED' | 'FAILED'
export type BackupVerifyStatus = 'UNVERIFIED' | 'VERIFIED' | 'CORRUPTED'

export interface BackupRecord {
  id: number
  instanceId: number
  fileName: string
  sizeBytes: number
  keyCount: number
  processedKeys?: number
  status: 'RUNNING' | 'SUCCESS' | 'FAILED'
  message?: string
  createdAt: string
  finishedAt?: string
  restoreStatus?: RestoreStatus
  restoreStartedAt?: string
  restoreFinishedAt?: string
  restoredKeys?: number
  restoreMessage?: string
  sha256?: string
  verifyStatus?: BackupVerifyStatus
  verifiedAt?: string
  preRestoreBackupId?: number
}

export interface BackupVerifyResult {
  backupId: number
  fileName: string
  status: BackupVerifyStatus
  expectedSha256?: string
  actualSha256: string
  validEntries: number
  sizeBytes: number
  verifiedAt: string
  message: string
}

export interface RestorePreviewView {
  backupId: number
  fileName: string
  verifyStatus?: BackupVerifyStatus
  archiveReadable: boolean
  totalKeys: number
  conflictKeys: number
  newKeys: number
  expiredKeys: number
  typeBreakdown: Record<string, number>
  sampleConflicts: string[]
  warning?: string
}

export interface RestoreRequest {
  createSafetySnapshot: boolean
}

export interface CheckpointResult {
  triggered: boolean
  message: string
  lastSaveTime: number
}

export interface CheckpointStatusView {
  supported: boolean
  inProgress: boolean
  lastSaveTime: number
  lastStatus: string
  lastDurationSeconds: number
  lastDurationMillis: number
  lastError?: string
}

export type BackupScheduleMode = 'LOGICAL' | 'CHECKPOINT'

export interface BackupScheduleView {
  id: number
  instanceId: number
  instanceName: string
  name: string
  cronExpression: string
  mode: BackupScheduleMode
  retentionCount: number
  enabled: boolean
  lastRunAt?: string
  nextRunAt?: string
  lastStatus?: 'SUCCESS' | 'FAILED' | 'SKIPPED' | string
  lastMessage?: string
  createdAt?: string
  updatedAt?: string
}

export interface BackupScheduleRequest {
  instanceId: number
  name: string
  cronExpression: string
  mode: BackupScheduleMode
  retentionCount: number
  enabled: boolean
}

export interface BackupScheduleRunResult {
  scheduleId: number
  mode: BackupScheduleMode
  status: string
  message?: string
  backupRecordId?: number
  executedAt?: string
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
  requestId?: string
}

export interface SystemInfo {
  instance: InstanceView
  server: { version: string; mode: string; uptimeSeconds: number; tcpPort: number; os: string; arch: string; jdk: string; processId: string }
  clients: { connected: number; activeRequests: number; totalConnections: number; errors: number }
  memory: { usedBytes: number; maxBytes: number; jvmUsedBytes: number; maxJvmHeapBytes: number }
  stats: { qps: number; instantaneousOpsPerSec: number; avgLatencyMs: number; totalKeys: number }
  keyspace: Array<{ db: string; keys: number; expires: number; avgTtl: number }>
  config: Record<string, string>
  warnings: string[]
  sections?: Record<string, Record<string, string>>
}

export interface SstableTable {
  id: number
  keys: number
  bytes: number
  /** 转义后的可读文本（非可打印字节为 \xNN） */
  firstKey: string
  lastKey: string
  /** 无损原始字节（Base64），用于复制 */
  firstKeyBase64: string
  lastKeyBase64: string
}

export interface SstableTableView {
  /** 内核声明的键比较器语义，当前恒为 bytewise */
  keyOrder: string
  count: number
  totalBytes: number
  totalEntries: number
  tables: SstableTable[]
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface SlowLogEntryView {
  id: number
  timestampSeconds: number
  durationMicros: number
  command: string
  args: string[]
}

export interface SlowLogView {
  supported: boolean
  totalLen: number
  entries: SlowLogEntryView[]
  message?: string
}

export interface VectorCollectionSummary {
  name: string
  count: number
}

export interface VectorCollectionsView {
  supported: boolean
  message?: string
  collections: VectorCollectionSummary[]
  totalVectors: number
}

export interface VectorSearchHit {
  id: string
  score: number
}

export interface VectorSearchResultView {
  supported: boolean
  message?: string
  collection: string
  topK: number
  costMs: number
  hits: VectorSearchHit[]
}

export interface TimeSeriesPoint {
  timestamp: number
  value: number
}

export interface TimeSeriesSummary {
  key: string
  length: number
  latestTimestamp?: number
  latestValue?: number
}

export interface TimeSeriesKeysView {
  supported: boolean
  message?: string
  series: TimeSeriesSummary[]
}

export interface TimeSeriesRangeView {
  supported: boolean
  message?: string
  key: string
  totalLen: number
  aggregation?: string
  bucketMs?: number
  points: TimeSeriesPoint[]
  reducedValue?: number
}

export interface JsonKeysView {
  supported: boolean
  message?: string
  keys: string[]
}

export interface JsonQueryView {
  supported: boolean
  message?: string
  exists: boolean
  key: string
  path: string
  valueType?: string
  rawJson?: string
  prettyJson?: string
  objectKeys: string[]
}

export interface RegionNodeView {
  index: number
  regionId: string
  host: string
  port: number
  slotCount: number
  slotPercent: number
}

export interface TransactionMetricsView {
  beginTxn: number
  activeTxn: number
  committedTxn: number
  rollbackTxn: number
  conflictTxn: number
  abortTxn: number
  recoveryTxn: number
  lockCount: number
  commitLatencyMs: number
  prepareLatencyMs: number
  networkRetry: number
  lockWaitMs: number
  regionCount: number
}

export interface PersistenceStatusView {
  bgsaveInProgress: boolean
  lastSaveTime: number
  lastStatus: string
  lastDurationMillis: number
  lastError?: string
}

export interface ClusterTopologyView {
  instanceId: number
  instanceName: string
  mode: 'DISTRIBUTED' | 'STANDALONE' | string
  distributed: boolean
  nodeId?: string
  version?: string
  totalKeys: number
  totalSlots: number
  regionCount: number
  regions: RegionNodeView[]
  transaction?: TransactionMetricsView
  persistence?: PersistenceStatusView
}

export interface K8sClusterSummary {
  name: string
  namespace: string
  phase: string
  image?: string
  metadataReplicas: number
  readyMetadata: number
  storageReplicas: number
  readyStorage: number
  gatewayReplicas: number
  readyGateway: number
  regionIds: string[]
  generation?: number
  observedGeneration?: number
  lastAction?: string
  lastBackupJob?: string
  lastBackupStatus?: string
  lastBackupTime?: string
  backupScheduleCron?: string
  message?: string
  createdAt?: string
}

export interface K8sClusterListView {
  enabled: boolean
  namespace: string
  apiServer: string
  message?: string
  clusters: K8sClusterSummary[]
}

export interface K8sBackupTriggerResult {
  clusterName: string
  namespace: string
  requestId: string
  message: string
}