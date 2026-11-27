import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import InstanceSummaryStrip from './InstanceSummaryStrip.vue'
import type { InstanceView, LiveMetrics } from '@/api/types'

const pushMock = vi.fn()

vi.mock('vue-router', () => ({
  useRouter: () => ({
    push: pushMock
  })
}))

const sampleInstance: InstanceView = {
  id: 1,
  name: 'prod-core-1',
  host: '10.0.0.10',
  port: 6379,
  status: 'UP',
  version: '6.4.0',
  tlsEnabled: false,
  enabled: true,
  hasPassword: false,
  hasTlsCaCert: false
}

const sampleLive: LiveMetrics = {
  version: '6.4.0',
  uptimeSeconds: 7200,
  totalKeys: 5000,
  usedMemory: 32 * 1024 * 1024,
  maxMemory: 128 * 1024 * 1024,
  usedMemoryJvm: 64 * 1024 * 1024,
  maxJvmHeap: 512 * 1024 * 1024,
  qps: 1234.5,
  opsPerSec: 1234.5,
  avgLatencyMs: 0.35,
  p50Millis: 0.2,
  p95Millis: 0.8,
  p99Millis: 1.4,
  latencyMaxMillis: 3.1,
  latencySamples: 100,
  connections: 24,
  activeRequests: 3,
  errors: 4,
  tieringReported: true,
  memtableUsedBytes: 1024,
  memtableMaxBytes: 4096,
  memtableEntries: 10,
  coldSstableCount: 1,
  coldDiskUsageBytes: 2048,
  coldShare: 0.66,
  flushCount: 1,
  flushBytes: 1024,
  flushInProgress: 0,
  compactionCount: 0,
  compactionInProgress: 0,
  compactionInputBytes: 0,
  compactionOutputBytes: 0,
  compactionLatencyMs: 0,
  migrationPending: 0,
  migrationSuccess: 1,
  migrationFailed: 0,
  cacheHitRate: 0.9,
  cacheHits: 90,
  cacheMisses: 10,
  cacheEvictions: 0,
  hotKeys: 10,
  coldEntries: 5,
  hotKeyShare: 0.66
}

describe('InstanceSummaryStrip', () => {
  beforeEach(() => {
    pushMock.mockReset()
  })

  it('displays live metrics, alert dot, error warning class, and navigates via quick chips', async () => {
    const wrapper = mount(InstanceSummaryStrip, {
      props: {
        selected: sampleInstance,
        live: sampleLive,
        firingAlerts: 2,
        offline: false
      }
    })

    expect(wrapper.text()).toContain('prod-core-1')
    expect(wrapper.text()).toContain('10.0.0.10:6379')
    expect(wrapper.text()).toContain('未恢复告警 2 条')
    expect(wrapper.find('.metric.warn').exists()).toBe(true)

    const buttons = wrapper.findAll('.quick-chip')
    await buttons[0].trigger('click')
    await buttons[1].trigger('click')
    await buttons[2].trigger('click')
    await buttons[3].trigger('click')
    await buttons[4].trigger('click')
    expect(pushMock.mock.calls.map((c) => c[0])).toEqual([
      '/keyspace',
      '/terminal',
      '/slowlog',
      '/backups',
      '/alerts'
    ])
  })

  it('masks metric values as em-dash when offline', () => {
    const wrapper = mount(InstanceSummaryStrip, {
      props: {
        selected: undefined,
        live: undefined,
        firingAlerts: 0,
        offline: true
      }
    })

    expect(wrapper.text()).toContain('未选择实例')
    expect(wrapper.text()).toContain('实例离线')
    expect(wrapper.findAll('.metric-value').every((n) => n.text() === '—')).toBe(true)
  })
})
