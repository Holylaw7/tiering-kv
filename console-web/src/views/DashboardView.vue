<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { dashboardApi, instanceApi } from '@/api'
import type { OverviewView, TrendPoint } from '@/api/types'
import { useInstanceStore } from '@/stores/instance'
import { usePolling } from '@/composables/usePolling'
import { formatUptime } from '@/utils/format'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import InstanceSummaryStrip from '@/components/dashboard/InstanceSummaryStrip.vue'
import TieringMemoryPanel from '@/components/dashboard/TieringMemoryPanel.vue'
import TrendChartsGrid from '@/components/dashboard/TrendChartsGrid.vue'
import SstableInventoryDrawer from '@/components/dashboard/SstableInventoryDrawer.vue'

const instanceStore = useInstanceStore()
const overview = ref<OverviewView>()
const trends = ref<TrendPoint[]>([])
const minutes = ref(30)
const autoRefresh = ref(true)
const loading = ref(false)
const tablesVisible = ref(false)

const live = computed(() => overview.value?.live)
const selected = computed(() => instanceStore.selected)
const instances = computed(() => overview.value?.instances ?? instanceStore.list)
const instanceOffline = computed(() => !live.value || selected.value?.status === 'DOWN')
const offline = computed(() => !live.value)

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    await instanceStore.load()
    const id = instanceStore.selectedId
    if (!id) {
      overview.value = undefined
      trends.value = []
      return
    }
    const [ov, tr] = await Promise.all([
      dashboardApi.overview(id),
      dashboardApi.trends(id, minutes.value)
    ])
    overview.value = ov
    trends.value = tr
    if (ov.selected) {
      instanceStore.syncInstance(
        ov.live && ov.selected.status === 'UNKNOWN' ? { ...ov.selected, status: 'UP' } : ov.selected
      )
    }
  } catch {
    // 全局拦截器已提示
  } finally {
    loading.value = false
  }
}

async function probe(id: number) {
  await instanceApi.probe(id)
  ElMessage.success('探测完成')
  await load(true)
}

const poller = usePolling(() => load(true), {
  intervalMs: 5000,
  pauseWhenHidden: true,
  refreshOnVisible: true
})

watch([() => instanceStore.selectedId, minutes], () => {
  void load()
})

watch(autoRefresh, (enabled) => {
  if (enabled) {
    poller.start()
  } else {
    poller.stop()
  }
})

onMounted(async () => {
  await load()
  if (autoRefresh.value) {
    poller.start()
  }
})
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-header">
      <div>
        <h2 class="page-title">运行总览</h2>
        <p class="page-subtitle">实时读取内核 INFO 指标（采集周期 5 秒，后台标签页自动暂停），按所选实例聚合。</p>
      </div>
      <div class="actions">
        <el-select v-model="minutes" size="small" style="width: 108px">
          <el-option :value="5" label="近 5 分钟" />
          <el-option :value="30" label="近 30 分钟" />
          <el-option :value="60" label="近 1 小时" />
          <el-option :value="180" label="近 3 小时" />
        </el-select>
        <el-switch v-model="autoRefresh" size="small" />
        <span class="switch-label">{{ autoRefresh ? '自动刷新' : '已暂停' }}</span>
        <el-button size="small" @click="load()">
          <el-icon><Refresh /></el-icon>
        </el-button>
      </div>
    </div>

    <InstanceSummaryStrip
      :selected="selected"
      :live="live"
      :firing-alerts="overview?.firingAlerts ?? 0"
      :offline="offline"
    />

    <TieringMemoryPanel
      :live="live"
      :instance-offline="instanceOffline"
      @open-tables="tablesVisible = true"
    />

    <TrendChartsGrid :trends="trends" :live="live" :offline="offline" />

    <section class="panel list-panel">
      <div class="panel-title">
        纳管实例
        <span class="hint">共 {{ instances.length }} 个</span>
      </div>
      <el-table :data="instances" size="small">
        <template #empty>
          <EmptyState title="还没有纳管实例" description="在「实例管理」中添加一个 Tiering-KV 实例后，这里会显示它的探测状态。" />
        </template>
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column label="地址" min-width="170">
          <template #default="{ row }">
            <span class="mono">{{ row.host }}:{{ row.port }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <StatusBadge
              :label="row.status"
              :tone="row.status === 'UP' ? 'success' : row.status === 'DOWN' ? 'danger' : 'neutral'"
              dot
            />
          </template>
        </el-table-column>
        <el-table-column label="版本" width="100">
          <template #default="{ row }">
            <span class="mono">{{ row.version || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="运行时长" width="130">
          <template #default>
            {{ formatUptime(live?.uptimeSeconds) }}
          </template>
        </el-table-column>
        <el-table-column prop="lastProbeAt" label="最近探测" width="180" />
        <el-table-column label="操作" width="100" align="right">
          <template #default="{ row }">
            <el-button link size="small" @click="probe(row.id)">探测</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <SstableInventoryDrawer v-model="tablesVisible" :instance-id="instanceStore.selectedId" />
  </div>
</template>

<style scoped>
.actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: none;
}

.switch-label {
  font-size: var(--text-sm);
  color: var(--text-2);
}

.list-panel {
  padding-bottom: 8px;
}
</style>
