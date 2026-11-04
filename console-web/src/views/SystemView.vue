<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { systemApi } from '@/api'
import type { SystemInfo } from '@/api/types'
import { useInstanceStore } from '@/stores/instance'
import { formatBytes } from '@/utils/format'

const store = useInstanceStore()
const info = ref<SystemInfo>()
const loading = ref(false)

async function load() {
  if (!store.selectedId) return
  loading.value = true
  try {
    info.value = await systemApi.info(store.selectedId)
  } finally {
    loading.value = false
  }
}

function uptime(seconds: number): string {
  if (!seconds) return '-'
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  return days > 0 ? `${days} 天 ${hours} 小时` : hours > 0 ? `${hours} 小时 ${minutes} 分` : `${minutes} 分`
}

function usage(used: number, max: number): number {
  if (!max) return 0
  return Math.min(100, Math.round((used / max) * 100))
}

watch(() => store.selectedId, load)
onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-header">
      <div>
        <h2 class="page-title">系统信息</h2>
        <p class="page-subtitle">
          直接读取内核 <span class="mono">INFO</span> 与 <span class="mono">CONFIG GET</span>（只读，不落库）
        </p>
      </div>
      <el-button @click="load">刷新</el-button>
    </div>

    <el-alert
      v-if="info?.warnings?.length"
      type="warning"
      :closable="false"
      show-icon
      class="warn"
      :title="'部分信息不可用：' + info.warnings.join('；')"
    />

    <div class="cards">
      <div class="panel card">
        <div class="card-title">服务端</div>
        <el-descriptions :column="1" size="small" border>
          <el-descriptions-item label="版本">{{ info?.server.version || '-' }}</el-descriptions-item>
          <el-descriptions-item label="运行模式">{{ info?.server.mode || '-' }}</el-descriptions-item>
          <el-descriptions-item label="运行时长">{{ uptime(info?.server.uptimeSeconds ?? 0) }}</el-descriptions-item>
          <el-descriptions-item label="监听端口">{{ info?.server.tcpPort || '-' }}</el-descriptions-item>
          <el-descriptions-item label="JDK">{{ info?.server.jdk || '-' }}</el-descriptions-item>
          <el-descriptions-item label="系统 / 架构">
            {{ info?.server.os || '-' }} / {{ info?.server.arch || '-' }} 位
          </el-descriptions-item>
          <el-descriptions-item label="进程 ID">{{ info?.server.processId || '-' }}</el-descriptions-item>
        </el-descriptions>
      </div>

      <div class="panel card">
        <div class="card-title">连接</div>
        <el-descriptions :column="1" size="small" border>
          <el-descriptions-item label="当前连接">{{ info?.clients.connected ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="处理中请求">{{ info?.clients.activeRequests ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="累计连接">{{ info?.clients.totalConnections ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="错误计数">{{ info?.clients.errors ?? 0 }}</el-descriptions-item>
        </el-descriptions>
      </div>

      <div class="panel card">
        <div class="card-title">内存</div>
        <el-descriptions :column="1" size="small" border>
          <el-descriptions-item label="引擎已用">{{ formatBytes(info?.memory.usedBytes) }}</el-descriptions-item>
          <el-descriptions-item label="引擎上限">{{ formatBytes(info?.memory.maxBytes) }}</el-descriptions-item>
          <el-descriptions-item label="JVM 已用">{{ formatBytes(info?.memory.jvmUsedBytes) }}</el-descriptions-item>
          <el-descriptions-item label="JVM 堆上限">{{ formatBytes(info?.memory.maxJvmHeapBytes) }}</el-descriptions-item>
          <el-descriptions-item label="引擎使用率">
            <el-progress
              :percentage="usage(info?.memory.usedBytes ?? 0, info?.memory.maxBytes ?? 0)"
              :stroke-width="10"
              :status="usage(info?.memory.usedBytes ?? 0, info?.memory.maxBytes ?? 0) > 85 ? 'exception' : undefined"
            />
          </el-descriptions-item>
        </el-descriptions>
      </div>

      <div class="panel card">
        <div class="card-title">吞吐</div>
        <el-descriptions :column="1" size="small" border>
          <el-descriptions-item label="QPS">{{ (info?.stats.qps ?? 0).toFixed(2) }}</el-descriptions-item>
          <el-descriptions-item label="瞬时 OPS">
            {{ (info?.stats.instantaneousOpsPerSec ?? 0).toFixed(2) }}
          </el-descriptions-item>
          <el-descriptions-item label="平均延迟">{{ (info?.stats.avgLatencyMs ?? 0).toFixed(3) }} ms</el-descriptions-item>
          <el-descriptions-item label="键总数">{{ info?.stats.totalKeys ?? 0 }}</el-descriptions-item>
        </el-descriptions>
      </div>
    </div>

    <div class="panel">
      <div class="card-title">键空间</div>
      <el-table :data="info?.keyspace ?? []" size="small" empty-text="暂无数据">
        <el-table-column prop="db" label="库" width="120" />
        <el-table-column prop="keys" label="键数" width="140" />
        <el-table-column prop="expires" label="带过期" width="140" />
        <el-table-column prop="avgTtl" label="平均 TTL (ms)" />
      </el-table>
    </div>

    <div class="panel">
      <div class="card-title">配置（只读）</div>
      <el-table
        :data="Object.entries(info?.config ?? {}).map(([key, value]) => ({ key, value }))"
        size="small"
        empty-text="内核未返回配置"
      >
        <el-table-column prop="key" label="配置项" width="220" />
        <el-table-column prop="value" label="值">
          <template #default="{ row }"><span class="mono">{{ row.value }}</span></template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(248px, 1fr));
  gap: 14px;
  margin-bottom: 14px;
}

.card-title {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 10px;
}

.warn {
  margin-bottom: 14px;
}
</style>
