import type { BackupRecord } from '@/api/types'

export function restorePercent(row: BackupRecord): number {
  const total = Math.max(1, row.keyCount || 0)
  const done = Math.max(0, row.restoredKeys || 0)
  if (row.restoreStatus === 'SUCCEEDED') return 100
  return Math.min(99, Math.round((done / total) * 100))
}

export function verifyBadgeLabel(status?: string): string {
  if (status === 'VERIFIED') return '已校验'
  if (status === 'CORRUPTED') return '已损坏'
  return '未校验'
}

export function verifyBadgeTone(status?: string): 'success' | 'danger' | 'neutral' {
  if (status === 'VERIFIED') return 'success'
  if (status === 'CORRUPTED') return 'danger'
  return 'neutral'
}

export function shortHash(hash?: string): string {
  if (!hash) return '—'
  return hash.length > 10 ? `${hash.slice(0, 10)}…` : hash
}
