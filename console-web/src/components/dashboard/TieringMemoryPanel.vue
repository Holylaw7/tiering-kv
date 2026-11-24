<script setup lang="ts">
import { computed } from 'vue'
import type { LiveMetrics } from '@/api/types'
import { formatBytes, formatNumber, formatPercent } from '@/utils/format'

const props = defineProps<{
  live?: LiveMetrics
  instanceOffline: boolean
}>()

const emit = defineEmits<{
  (e: 'open-tables'): void
}>()

const tiering = computed(() => {
  const current = props.live
  const hot = current?.memtableUsedBytes ?? 0
  const cold = current?.coldDiskUsageBytes ?? 0
  const total = hot + cold
  return {
    hot,
    cold,
    total,
    hotShare: total ? hot / total : 0,
    coldShare: total ? cold / total : 0,
    entries: current?.memtableEntries ?? 0,
    sstables: current?.coldSstableCount ?? 0,
    flushCount: current?.flushCount ?? 0,
    flushBytes: current?.flushBytes ?? 0,
    pending: current?.migrationPending ?? 0,
    migrated: current?.migrationSuccess ?? 0,
    cacheHitRate: current?.cacheHitRate ?? 0,
    cacheHits: current?.cacheHits ?? 0,
    cacheMisses: current?.cacheMisses ?? 0,
    cacheEvictions: current?.cacheEvictions ?? 0,
    hotKeys: current?.hotKeys ?? 0,
    coldEntries: current?.coldEntries ?? 0,
    hotKeyShare: current?.hotKeyShare ?? 0,
    compactionCount: current?.compactionCount ?? 0
  }
})

const tieringReported = computed(() => props.live?.tieringReported === true)
const tieringBusy = computed(
  () => (props.live?.flushInProgress ?? 0) > 0 || (props.live?.compactionInProgress ?? 0) > 0
)
const memtableRatio = computed(() => {
  const current = props.live
  if (!current || !current.memtableMaxBytes) return 0
  return Math.min(1, Math.max(0, current.memtableUsedBytes / current.memtableMaxBytes))
})
const memtableRatioTone = computed<'normal' | 'warn' | 'danger'>(() => {
  const pct = memtableRatio.value * 100
  if (pct >= 95) return 'danger'
  if (pct >= 70) return 'warn'
  return 'normal'
})
</script>

<template>
  <section class="tiering">
    <div class="tiering-head">
      <h3>存储分层</h3>
      <span class="hint">MemTable → Flush → SSTable，数据来自内核 <span class="mono">INFO # Tiering</span></span>
      <span v-if="tieringBusy" class="busy">
        <span class="busy-dot"></span>
        {{ (live?.compactionInProgress ?? 0) > 0 ? 'Compaction 进行中' : 'Flush 进行中' }}
      </span>
    </div>

    <div v-if="instanceOffline && !live" class="tiering-empty is-error">
      实例不可达：未取到 INFO，分层指标不可用。请检查实例状态后重试。
    </div>
    <div v-else-if="!tieringReported" class="tiering-empty">
      当前内核未上报分层指标（INFO 中无 <span class="mono"># Tiering</span> 段），此处不展示推算值。
    </div>

    <template v-else>
      <div class="pipeline">
        <div class="stage">
          <div class="stage-head">
            <span class="stage-name">MemTable</span>
            <span class="stage-tag hot">热层</span>
          </div>
          <div class="stage-value num">{{ formatBytes(tiering.hot) }}</div>
          <div class="stage-meta">
            {{ formatNumber(tiering.entries) }} 个条目 · 上限 {{ formatBytes(live?.memtableMaxBytes) }}
          </div>
        </div>

        <div class="stage-arrow">
          <span class="arrow-label">Flush {{ formatNumber(tiering.flushCount) }} 次</span>
          <span class="arrow"></span>
          <span class="arrow-sub num">{{ formatBytes(tiering.flushBytes) }}</span>
        </div>

        <div class="stage">
          <div class="stage-head">
            <span class="stage-name">SSTable</span>
            <span class="stage-tag cold">冷层</span>
          </div>
          <div class="stage-value num">{{ formatBytes(tiering.cold) }}</div>
          <div class="stage-meta">
            <button class="link-btn" type="button" @click="emit('open-tables')">
              {{ formatNumber(tiering.sstables) }} 个表
            </button>
            · 占存储 {{ formatPercent(tiering.coldShare) }}
          </div>
        </div>

        <div class="stage-arrow">
          <span class="arrow-label">迁移 {{ formatNumber(tiering.migrated) }} 次</span>
          <span class="arrow"></span>
          <span class="arrow-sub num">待处理 {{ tiering.pending }}</span>
        </div>

        <div class="stage">
          <div class="stage-head">
            <span class="stage-name">BlockCache</span>
            <span class="stage-tag cache">读缓存</span>
          </div>
          <div class="stage-value num">{{ formatPercent(tiering.cacheHitRate) }}</div>
          <div class="stage-meta">
            命中 {{ formatNumber(tiering.cacheHits) }} · 未命中 {{ formatNumber(tiering.cacheMisses) }}
          </div>
        </div>
      </div>

      <div class="composition">
        <div class="comp-head">
          <span class="comp-title">热层内存水位与存储构成</span>
          <span class="comp-note">
            键分布取 SSTable 表条目累加（含历史版本），非去重精确值
          </span>
        </div>

        <div class="comp-row watermark-row">
          <span class="comp-label">热水位</span>
          <div class="watermark-track">
            <div
              class="watermark-fill"
              :class="'is-' + memtableRatioTone"
              :style="{ width: (memtableRatio * 100).toFixed(1) + '%' }"
            ></div>
            <span class="wm-tick" style="left: 70%" title="70% 触发 Flush"><i>70%</i></span>
            <span class="wm-tick" style="left: 85%" title="85% 异步迁移"><i>85%</i></span>
            <span class="wm-tick is-crit" style="left: 95%" title="95% 写背压"><i>95%</i></span>
          </div>
          <span class="comp-value num">
            {{ formatPercent(memtableRatio) }} · {{ formatBytes(tiering.hot) }} / {{ formatBytes(live?.memtableMaxBytes) }}
          </span>
        </div>

        <div class="comp-row">
          <span class="comp-label">容量</span>
          <div class="comp-bar" :class="{ empty: tiering.total === 0 }">
            <template v-if="tiering.total > 0">
              <div v-if="tiering.hot > 0" class="comp-hot" :style="{ width: Math.max(1, tiering.hotShare * 100) + '%' }"></div>
              <div class="comp-cold" :style="{ width: Math.max(1, tiering.coldShare * 100) + '%' }"></div>
            </template>
          </div>
          <span class="comp-value num">
            <i class="dot hot"></i>{{ formatBytes(tiering.hot) }}
            <i class="dot cold"></i>{{ formatBytes(tiering.cold) }}
          </span>
        </div>

        <div class="comp-row">
          <span class="comp-label">键</span>
          <div class="comp-bar" :class="{ empty: tiering.hotKeys + tiering.coldEntries === 0 }">
            <template v-if="tiering.hotKeys + tiering.coldEntries > 0">
              <div v-if="tiering.hotKeys > 0" class="comp-hot" :style="{ width: Math.max(1, tiering.hotKeyShare * 100) + '%' }"></div>
              <div class="comp-cold" :style="{ width: Math.max(1, (1 - tiering.hotKeyShare) * 100) + '%' }"></div>
            </template>
          </div>
          <span class="comp-value num">
            <i class="dot hot"></i>{{ formatNumber(tiering.hotKeys) }}
            <i class="dot cold"></i>{{ formatNumber(tiering.coldEntries) }}
          </span>
        </div>

        <div class="comp-foot">
          <span>总占用 {{ formatBytes(tiering.total) }}</span>
          <span v-if="tiering.total === 0">实例为空，尚无分层数据</span>
          <span v-else>
            热层占容量 {{ formatPercent(tiering.hotShare) }} · 键总数（去重）{{ formatNumber(live?.totalKeys) }}
          </span>
          <span>Compaction {{ formatNumber(tiering.compactionCount) }} 次 · 淘汰 {{ formatNumber(tiering.cacheEvictions) }}</span>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.tiering {
  margin-bottom: 22px;
}

.tiering-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 10px;
}

.tiering-head h3 {
  margin: 0;
  font-size: var(--text-base);
  font-weight: 600;
  color: var(--text);
}

.tiering-head .hint {
  font-size: var(--text-sm);
  color: var(--text-3);
}

.busy {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  color: var(--warn);
}

.busy-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--warn);
  animation: pulse 1.4s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 0.35; }
  50% { opacity: 1; }
}

.tiering-empty {
  padding: 16px 18px;
  border: 1px dashed var(--line);
  border-radius: var(--radius);
  font-size: var(--text-sm);
  color: var(--text-3);
  background: var(--surface-2);
}

.tiering-empty.is-error {
  border-color: #f3cfcf;
  background: var(--danger-soft);
  color: var(--danger);
}

.pipeline {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 128px minmax(0, 1fr) 128px minmax(0, 1fr);
  align-items: stretch;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  overflow: hidden;
}

.stage {
  padding: 14px 16px 16px;
  min-width: 0;
}

.stage-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.stage-name {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text);
}

.stage-tag {
  padding: 0 6px;
  height: 18px;
  line-height: 18px;
  border-radius: 3px;
  font-size: 10.5px;
  letter-spacing: 0.03em;
}

.stage-tag.hot {
  background: var(--tier-hot-soft);
  color: var(--tier-hot);
}

.stage-tag.cold {
  background: var(--tier-cold-soft);
  color: var(--tier-cold);
}

.stage-tag.cache {
  background: var(--tier-cache-soft);
  color: var(--tier-cache);
}

.stage-value {
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--text);
  line-height: 1.25;
}

.stage-meta {
  margin-top: 3px;
  font-size: 11.5px;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.stage-arrow {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 0 6px;
  border-left: 1px solid var(--line-soft);
  border-right: 1px solid var(--line-soft);
  background: var(--surface-2);
}

.arrow-label,
.arrow-sub {
  font-size: 10.5px;
  color: var(--text-3);
  white-space: nowrap;
}

.arrow {
  position: relative;
  width: 52px;
  height: 1px;
  background: var(--line-strong);
}

.arrow::after {
  content: '';
  position: absolute;
  right: -1px;
  top: -3px;
  border-left: 5px solid var(--line-strong);
  border-top: 3.5px solid transparent;
  border-bottom: 3.5px solid transparent;
}

.composition {
  margin-top: 8px;
  padding: 14px 16px 12px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
}

.comp-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.comp-title {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text);
}

.comp-note {
  font-size: 11px;
  color: var(--text-3);
}

.comp-row {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr) 220px;
  align-items: center;
  gap: 14px;
}

.comp-row + .comp-row {
  margin-top: 8px;
}

.comp-label {
  font-size: 11.5px;
  color: var(--text-3);
}

.comp-bar {
  display: flex;
  height: 8px;
  border-radius: 999px;
  overflow: hidden;
  background: var(--surface-3);
}

.comp-bar.empty {
  background: repeating-linear-gradient(90deg, var(--surface-3) 0 6px, transparent 6px 12px);
}

.comp-hot {
  background: var(--tier-hot);
}

.comp-cold {
  background: var(--tier-cold);
}

.comp-value {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  font-size: 11.5px;
  color: var(--text-2);
  white-space: nowrap;
}

.comp-value .dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.comp-value .dot.hot {
  background: var(--tier-hot);
  margin-left: 8px;
}

.comp-value .dot.hot:first-child {
  margin-left: 0;
}

.comp-value .dot.cold {
  background: var(--tier-cold);
  margin-left: 8px;
}

.watermark-row {
  margin-bottom: 12px;
  padding-bottom: 14px;
  border-bottom: 1px dashed var(--line-soft);
}

.watermark-track {
  position: relative;
  height: 10px;
  border-radius: 999px;
  background: var(--surface-3);
}

.watermark-fill {
  height: 100%;
  border-radius: 999px;
  background: var(--tier-cold);
  transition: width 0.25s var(--ease);
}

.watermark-fill.is-warn {
  background: var(--tier-hot);
}

.watermark-fill.is-danger {
  background: var(--danger);
}

.wm-tick {
  position: absolute;
  top: -2px;
  bottom: -2px;
  width: 1px;
  background: var(--text-3);
}

.wm-tick.is-crit {
  background: var(--danger);
}

.wm-tick i {
  position: absolute;
  top: 13px;
  left: 50%;
  transform: translateX(-50%);
  font-style: normal;
  font-size: 9.5px;
  color: var(--text-3);
  font-family: var(--font-mono);
}

.wm-tick.is-crit i {
  color: var(--danger);
}

.link-btn {
  border: 0;
  padding: 0;
  background: transparent;
  color: var(--accent);
  font-size: inherit;
  cursor: pointer;
}

.link-btn:hover {
  text-decoration: underline;
}

.comp-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--line-soft);
  font-size: 11px;
  color: var(--text-3);
}

@media (max-width: 1280px) {
  .pipeline {
    grid-template-columns: minmax(0, 1fr) 92px minmax(0, 1fr);
  }

  .stage-arrow:last-of-type {
    display: none;
  }

  .stage:last-of-type {
    grid-column: 1 / -1;
    border-top: 1px solid var(--line-soft);
  }
}

@media (max-width: 720px) {
  .pipeline {
    grid-template-columns: 1fr;
  }

  .stage-arrow {
    display: none;
  }

  .stage + .stage {
    border-top: 1px solid var(--line-soft);
  }

  .comp-row {
    grid-template-columns: 1fr;
    gap: 6px;
  }

  .comp-value {
    justify-content: flex-start;
  }
}
</style>
