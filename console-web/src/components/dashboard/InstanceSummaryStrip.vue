<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import type { InstanceView, LiveMetrics } from '@/api/types'
import { formatBytes, formatNumber, formatPercent, formatUptime } from '@/utils/format'

const props = defineProps<{
  selected?: InstanceView
  live?: LiveMetrics
  firingAlerts: number
  offline: boolean
}>()

const router = useRouter()

const show = (text: string) => (props.offline ? '—' : text)

const memoryRatio = computed(() => {
  const current = props.live
  if (!current || !current.maxMemory) return 0
  return current.usedMemory / current.maxMemory
})
</script>

<template>
  <div>
    <div class="status-strip">
      <span class="status-item">
        <span class="dot" :class="(selected?.status ?? 'unknown').toLowerCase()"></span>
        <strong>{{ selected?.name ?? '未选择实例' }}</strong>
      </span>
      <span class="status-item mono">{{ selected?.host }}:{{ selected?.port }}</span>
      <span class="status-item">版本 {{ live?.version || selected?.version || '—' }}</span>
      <span class="status-item">运行 {{ formatUptime(live?.uptimeSeconds) }}</span>
      <span class="status-item">连接 {{ live?.connections ?? 0 }} · 活跃 {{ live?.activeRequests ?? 0 }}</span>
      <span class="status-item grow"></span>
      <span class="status-item">
        <span class="dot" :class="firingAlerts > 0 ? 'down' : 'up'"></span>
        未恢复告警 {{ firingAlerts }} 条
      </span>
    </div>

    <div class="quick-nav">
      <span class="quick-label">快速直达</span>
      <button type="button" class="quick-chip" @click="router.push('/keyspace')">键空间维护</button>
      <button type="button" class="quick-chip" @click="router.push('/terminal')">在线终端</button>
      <button type="button" class="quick-chip" @click="router.push('/slowlog')">慢查询诊断</button>
      <button type="button" class="quick-chip" @click="router.push('/backups')">备份与 Checkpoint</button>
      <button type="button" class="quick-chip" @click="router.push('/alerts')">告警事件</button>
    </div>

    <div class="metrics">
      <div class="metric">
        <span class="metric-label">QPS</span>
        <span class="metric-value num">{{ show(formatNumber(live?.qps, 1)) }}</span>
        <span class="metric-hint">{{ offline ? '实例离线' : '近 5 秒均摊' }}</span>
      </div>
      <div class="metric">
        <span class="metric-label">延迟 P95</span>
        <span class="metric-value num">{{ show((live?.p95Millis ?? 0).toFixed(2)) }}<small v-if="!offline">ms</small></span>
        <span class="metric-hint" v-if="!offline">
          平均 {{ (live?.avgLatencyMs ?? 0).toFixed(2) }} · P99 {{ (live?.p99Millis ?? 0).toFixed(2) }} ms
        </span>
        <span class="metric-hint" v-else>实例离线</span>
      </div>
      <div class="metric">
        <span class="metric-label">键总数</span>
        <span class="metric-value num">{{ show(formatNumber(live?.totalKeys)) }}</span>
        <span class="metric-hint">db0 键空间</span>
      </div>
      <div class="metric">
        <span class="metric-label">引擎内存</span>
        <span class="metric-value num">{{ show(formatBytes(live?.usedMemory)) }}</span>
        <span class="metric-hint">{{ offline ? '实例离线' : '水位 ' + formatPercent(memoryRatio) }}</span>
      </div>
      <div class="metric">
        <span class="metric-label">JVM 堆</span>
        <span class="metric-value num">{{ show(formatBytes(live?.usedMemoryJvm)) }}</span>
        <span class="metric-hint">{{ offline ? '实例离线' : '上限 ' + formatBytes(live?.maxJvmHeap) }}</span>
      </div>
      <div class="metric" :class="{ warn: (live?.errors ?? 0) > 0 }">
        <span class="metric-label">错误计数</span>
        <span class="metric-value num">{{ show(formatNumber(live?.errors)) }}</span>
        <span class="metric-hint">累计命令错误</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.status-strip {
  display: flex;
  align-items: center;
  gap: 22px;
  padding-bottom: 14px;
  font-size: var(--text-sm);
  color: var(--text-2);
  flex-wrap: wrap;
}

.status-item {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  white-space: nowrap;
}

.status-item strong {
  font-weight: 600;
  color: var(--text);
}

.grow {
  flex: 1;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--text-3);
  flex: none;
}

.dot.up {
  background: var(--success);
}

.dot.down {
  background: var(--danger);
}

.quick-nav {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 18px;
}

.quick-label {
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--text-3);
  letter-spacing: 0.04em;
  margin-right: 4px;
}

.quick-chip {
  height: 26px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface);
  color: var(--text-2);
  font-size: var(--text-xs);
  cursor: pointer;
  transition: border-color 0.14s var(--ease), color 0.14s var(--ease), background-color 0.14s var(--ease);
}

.quick-chip:hover {
  border-color: var(--accent-line);
  background: var(--accent-soft);
  color: var(--accent);
}

.metrics {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  margin-bottom: 22px;
}

.metric {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 16px 20px 18px;
  border-left: 1px solid var(--line-soft);
  min-width: 0;
}

.metric:first-child {
  border-left: none;
  padding-left: 2px;
}

.metric-label {
  font-size: var(--text-xs);
  font-weight: 600;
  letter-spacing: 0.05em;
  text-transform: uppercase;
  color: var(--text-3);
}

.metric-value {
  font-size: 24px;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--text);
  line-height: 1.2;
}

.metric-value small {
  margin-left: 3px;
  font-size: 12px;
  font-weight: 500;
  color: var(--text-3);
}

.metric-hint {
  font-size: var(--text-xs);
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric.warn .metric-value {
  color: var(--danger);
}

@media (max-width: 1280px) {
  .metrics {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .metric:nth-child(4) {
    border-left: none;
    padding-left: 2px;
  }

  .metric:nth-child(n + 4) {
    border-top: 1px solid var(--line-soft);
  }
}

@media (max-width: 720px) {
  .metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .metric:nth-child(odd) {
    border-left: none;
    padding-left: 2px;
  }

  .metric:nth-child(n + 3) {
    border-top: 1px solid var(--line-soft);
  }
}
</style>
