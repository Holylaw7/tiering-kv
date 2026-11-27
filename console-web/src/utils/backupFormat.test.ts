import { describe, expect, it } from 'vitest'
import type { BackupRecord } from '@/api/types'
import { restorePercent, shortHash, verifyBadgeLabel, verifyBadgeTone } from './backupFormat'

function makeRecord(overrides: Partial<BackupRecord> = {}): BackupRecord {
  return {
    id: 1,
    instanceId: 10,
    fileName: 'backup-1.jsonl.gz',
    sizeBytes: 1024,
    keyCount: 100,
    status: 'SUCCESS',
    createdAt: '2026-11-27 10:00:00',
    ...overrides
  }
}

describe('backupFormat utilities', () => {
  it('computes restore percentage across running, edge, and succeeded states', () => {
    expect(restorePercent(makeRecord({ restoreStatus: 'SUCCEEDED', restoredKeys: 50, keyCount: 100 }))).toBe(100)
    expect(restorePercent(makeRecord({ restoreStatus: 'RESTORING', restoredKeys: 35, keyCount: 100 }))).toBe(35)
    expect(restorePercent(makeRecord({ restoreStatus: 'RESTORING', restoredKeys: 120, keyCount: 100 }))).toBe(99)
    expect(restorePercent(makeRecord({ restoreStatus: 'RESTORING', restoredKeys: -5, keyCount: 0 }))).toBe(0)
    expect(restorePercent(makeRecord({ restoreStatus: 'RESTORING', restoredKeys: undefined, keyCount: 0 }))).toBe(0)
  })

  it('maps verify status to Chinese labels and UI tones', () => {
    expect(verifyBadgeLabel('VERIFIED')).toBe('已校验')
    expect(verifyBadgeLabel('CORRUPTED')).toBe('已损坏')
    expect(verifyBadgeLabel('UNVERIFIED')).toBe('未校验')
    expect(verifyBadgeLabel(undefined)).toBe('未校验')

    expect(verifyBadgeTone('VERIFIED')).toBe('success')
    expect(verifyBadgeTone('CORRUPTED')).toBe('danger')
    expect(verifyBadgeTone('UNVERIFIED')).toBe('neutral')
    expect(verifyBadgeTone(undefined)).toBe('neutral')
  })

  it('truncates long SHA-256 hashes and falls back cleanly', () => {
    expect(shortHash(undefined)).toBe('—')
    expect(shortHash('')).toBe('—')
    expect(shortHash('abcdef1234')).toBe('abcdef1234')
    expect(shortHash('0123456789abcdef')).toBe('0123456789…')
  })
})
