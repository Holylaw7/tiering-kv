import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TieringMemoryPanel from './TieringMemoryPanel.vue'
import type { LiveMetrics } from '@/api/types'

function makeLive(overrides: Partial<LiveMetrics> = {}): LiveMetrics {
  return {
    version: '6.4.0',
    uptimeSeconds: 3600,
    totalKeys: 1200,
    usedMemory: 64 * 1024 * 1024,
    maxMemory: 128 * 1024 * 1024,
    usedMemoryJvm: 96 * 1024 * 1024,
    maxJvmHeap: 512 * 1024 * 1024,
    qps: 420.5,
    opsPerSec: 420.5,
    avgLatencyMs: 0.42,
    p50Millis: 0.3,
    p95Millis: 0.9,
    p99Millis: 1.5,
    latencyMaxMillis: 4.2,
    latencySamples: 200,
    connections: 12,
    activeRequests: 2,
    errors: 0,
    tieringReported: true,
    memtableUsedBytes: 50 * 1024 * 1024,
    memtableMaxBytes: 100 * 1024 * 1024,
    memtableEntries: 800,
    coldSstableCount: 5,
    coldDiskUsageBytes: 150 * 1024 * 1024,
    coldShare: 0.75,
    flushCount: 14,
    flushBytes: 200 * 1024 * 1024,
    flushInProgress: 0,
    compactionCount: 3,
    compactionInProgress: 0,
    compactionInputBytes: 1024,
    compactionOutputBytes: 512,
    compactionLatencyMs: 12,
    migrationPending: 2,
    migrationSuccess: 28,
    migrationFailed: 0,
    cacheHitRate: 0.875,
    cacheHits: 700,
    cacheMisses: 100,
    cacheEvictions: 12,
    hotKeys: 800,
    coldEntries: 400,
    hotKeyShare: 0.6667,
    ...overrides
  }
}

describe('TieringMemoryPanel', () => {
  it('renders offline error banner when instance is unreachable and live metrics are absent', () => {
    const wrapper = mount(TieringMemoryPanel, {
      props: { instanceOffline: true, live: undefined }
    })
    expect(wrapper.find('.tiering-empty.is-error').text()).toContain('实例不可达')
    expect(wrapper.find('.pipeline').exists()).toBe(false)
  })

  it('renders unreported hint when kernel omits # Tiering section', () => {
    const wrapper = mount(TieringMemoryPanel, {
      props: { instanceOffline: false, live: makeLive({ tieringReported: false }) }
    })
    expect(wrapper.find('.tiering-empty').text()).toContain('当前内核未上报分层指标')
  })

  it('renders tiering pipeline, watermark tones, busy badge, and emits open-tables on click', async () => {
    const wrapper = mount(TieringMemoryPanel, {
      props: { instanceOffline: false, live: makeLive({ flushInProgress: 1 }) }
    })

    expect(wrapper.find('.busy').text()).toContain('Flush 进行中')
    expect(wrapper.find('.watermark-fill').classes()).toContain('is-normal')
    expect(wrapper.text()).toContain('5 个表')

    await wrapper.find('.link-btn').trigger('click')
    expect(wrapper.emitted('open-tables')).toHaveLength(1)

    // >= 70% switches tone to warn, compactionInProgress overrides busy label
    await wrapper.setProps({
      live: makeLive({
        memtableUsedBytes: 75 * 1024 * 1024,
        memtableMaxBytes: 100 * 1024 * 1024,
        compactionInProgress: 1
      })
    })
    expect(wrapper.find('.watermark-fill').classes()).toContain('is-warn')
    expect(wrapper.find('.busy').text()).toContain('Compaction 进行中')

    // >= 95% switches tone to danger
    await wrapper.setProps({
      live: makeLive({
        memtableUsedBytes: 98 * 1024 * 1024,
        memtableMaxBytes: 100 * 1024 * 1024
      })
    })
    expect(wrapper.find('.watermark-fill').classes()).toContain('is-danger')

    // Empty instance zero-total state
    await wrapper.setProps({
      live: makeLive({
        memtableUsedBytes: 0,
        coldDiskUsageBytes: 0,
        hotKeys: 0,
        coldEntries: 0
      })
    })
    expect(wrapper.find('.comp-foot').text()).toContain('实例为空，尚无分层数据')
  })
})
