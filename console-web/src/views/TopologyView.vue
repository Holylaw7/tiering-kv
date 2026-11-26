<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Finished, Refresh, Setting, Upload } from '@element-plus/icons-vue'
import type { EChartsOption } from 'echarts'
import { clusterApi } from '@/api'
import type {
  ClusterTopologyView,
  K8sClusterListView,
  K8sClusterSummary
} from '@/api/types'
import { useInstanceStore } from '@/stores/instance'
import { useAuthStore } from '@/stores/auth'
import ChartPanel from '@/components/ChartPanel.vue'
import EmptyState from '@/components/EmptyState.vue'
import PageToolbar from '@/components/PageToolbar.vue'
import StatCard from '@/components/StatCard.vue'
import StatusBadge from '@/components/StatusBadge.vue'

const store = useInstanceStore()
const auth = useAuthStore()

const loading = ref(false)
const topology = ref<ClusterTopologyView | null>(null)
const topologyError = ref('')
const k8sView = ref<K8sClusterListView | null>(null)

// K8s 扩容与滚动升级对话框（ref 避免 Volar 3.3 reactive 收窄退化）
const scaleDialogVisible = ref(false)
const scaleSaving = ref(false)
const scaleForm = ref({
  name: '',
  storageReplicas: 3,
  gatewayReplicas: 1,
  metadataReplicas: 1
})

const upgradeDialogVisible = ref(false)
const upgradeSaving = ref(false)
const upgradeForm = ref({
  name: '',
  image: ''
})

const backingUpCluster = ref<string | null>(null)

const slotChartOption = computed<EChartsOption>(() => {
  const regions = topology.value?.regions ?? []
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' }
    },
    grid: { left: 54, right: 20, top: 20, bottom: 32 },
    xAxis: {
      type: 'category',
      data: regions.map((r) => `${r.regionId} (${r.host}:${r.port})`),
      axisLabel: { color: '#94a3b8', fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      name: 'HashSlots',
      axisLabel: { color: '#94a3b8', fontSize: 11 },
      splitLine: { lineStyle: { color: 'rgba(148,163,184,0.14)' } }
    },
    series: [
      {
        name: '槽位数',
        type: 'bar',
        barMaxWidth: 52,
        data: regions.map((r) => r.slotCount),
        itemStyle: { borderRadius: [4, 4, 0, 0] }
      }
    ]
  }
})

const lastSaveText = computed(() => {
  const p = topology.value?.persistence
  if (!p || !p.lastSaveTime) return '尚未触发 BGSAVE'
  return new Date(p.lastSaveTime * 1000).toLocaleString('zh-CN', { hour12: false })
})

async function load() {
  loading.value = true
  topologyError.value = ''
  try {
    const tasks: Array<Promise<void>> = [
      clusterApi.k8sClusters().then((res) => {
        k8sView.value = res
      })
    ]
    if (store.selectedId) {
      tasks.push(
        clusterApi
          .topology(store.selectedId)
          .then((res) => {
            topology.value = res
          })
          .catch((err: unknown) => {
            topology.value = null
            topologyError.value = err instanceof Error ? err.message : '集群拓扑读取失败'
          })
      )
    } else {
      topology.value = null
    }
    await Promise.all(tasks)
  } finally {
    loading.value = false
  }
}

function openScale(row: K8sClusterSummary) {
  scaleForm.value = {
    name: row.name,
    storageReplicas: row.storageReplicas,
    gatewayReplicas: row.gatewayReplicas,
    metadataReplicas: row.metadataReplicas
  }
  scaleDialogVisible.value = true
}

async function submitScale() {
  scaleSaving.value = true
  try {
    await clusterApi.scaleK8sCluster(scaleForm.value.name, {
      storageReplicas: scaleForm.value.storageReplicas,
      gatewayReplicas: scaleForm.value.gatewayReplicas,
      metadataReplicas: scaleForm.value.metadataReplicas
    })
    ElMessage.success(`已下发 ${scaleForm.value.name} 在线扩缩容指令，Operator 正在协调`)
    scaleDialogVisible.value = false
    await load()
  } finally {
    scaleSaving.value = false
  }
}

function openUpgrade(row: K8sClusterSummary) {
  upgradeForm.value = {
    name: row.name,
    image: row.image || ''
  }
  upgradeDialogVisible.value = true
}

async function submitUpgrade() {
  if (!upgradeForm.value.image.trim()) {
    ElMessage.warning('请填写目标镜像地址')
    return
  }
  upgradeSaving.value = true
  try {
    await clusterApi.upgradeK8sCluster(upgradeForm.value.name, upgradeForm.value.image.trim())
    ElMessage.success(`已触发 ${upgradeForm.value.name} 镜像滚动升级`)
    upgradeDialogVisible.value = false
    await load()
  } finally {
    upgradeSaving.value = false
  }
}

async function triggerK8sBackup(row: K8sClusterSummary) {
  await ElMessageBox.confirm(
    `确认为集群「${row.name}」通过 Operator CR 注解触发按需备份 Job？`,
    '触发按需备份',
    { type: 'info' }
  )
  backingUpCluster.value = row.name
  try {
    const res = await clusterApi.backupK8sCluster(row.name)
    ElMessage.success(res.message)
    await load()
  } finally {
    backingUpCluster.value = null
  }
}

watch(() => store.selectedId, load)
onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-header">
      <div>
        <h2 class="page-title">分布式集群拓扑与云原生控制</h2>
        <p class="page-subtitle">
          实时解析内核 # Cluster / # Transaction / # Persistence 与 16384 HashSlot 分布，并支持零依赖直连 Kubernetes Operator 扩缩容、升级与按需备份。
        </p>
      </div>
      <div class="header-actions">
        <el-button @click="load">
          <el-icon><Refresh /></el-icon> 刷新拓扑
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="topologyError"
      type="error"
      show-icon
      :closable="false"
      class="gap-bottom"
      :title="topologyError"
    />

    <div class="kpi-grid">
      <StatCard
        label="运行模式"
        :value="topology ? topology.mode : '—'"
        :tone="topology?.distributed ? 'accent' : 'default'"
        :hint="topology ? `节点 ${topology.nodeId || topology.instanceName} · v${topology.version || '-'}` : '请选择实例'"
      />
      <StatCard
        label="Region 分片"
        :value="topology ? `${topology.regionCount} 个` : '—'"
        :hint="topology ? `覆盖 ${topology.totalSlots.toLocaleString()} HashSlots` : '16384 槽位路由'"
      />
      <StatCard
        label="集群活跃键"
        :value="topology ? topology.totalKeys.toLocaleString() : '—'"
        :hint="topology?.persistence ? `最近快照：${lastSaveText}` : '实时 Keyspace 聚合'"
      />
      <StatCard
        label="K8s Operator 桥接"
        :value="k8sView?.enabled ? `${k8sView.clusters.length} 套集群` : '未接入'"
        :tone="k8sView?.enabled ? 'success' : 'default'"
        :hint="k8sView?.enabled ? `命名空间 ${k8sView.namespace}` : '纯 JDK HttpClient · 零外部依赖'"
      />
    </div>

    <div class="topology-grid gap-bottom">
      <ChartPanel
        title="16384 HashSlot 分片分布"
        :hint="topology?.distributed ? 'CRC16(key) % N 均匀路由' : '单机模式独占全部 16384 槽位'"
        :option="slotChartOption"
        height="260px"
      />

      <div class="panel card-pad">
        <h3 class="section-title">Region 节点清单</h3>
        <el-table :data="topology?.regions ?? []" size="small">
          <template #empty>
            <EmptyState title="暂无 Region 数据" description="请确保当前选中实例在线并支持 INFO 命令。" />
          </template>
          <el-table-column prop="regionId" label="Region" width="110">
            <template #default="{ row }">
              <StatusBadge :label="row.regionId" :tone="topology?.distributed ? 'accent' : 'neutral'" mono />
            </template>
          </el-table-column>
          <el-table-column label="节点地址" min-width="180">
            <template #default="{ row }">
              <span class="mono">{{ row.host }}:{{ row.port }}</span>
            </template>
          </el-table-column>
          <el-table-column label="槽位数" width="90" align="right">
            <template #default="{ row }"><span class="num">{{ row.slotCount }}</span></template>
          </el-table-column>
          <el-table-column label="占比" width="150">
            <template #default="{ row }">
              <div class="pct-cell">
                <el-progress :percentage="Math.round(row.slotPercent)" :stroke-width="6" :show-text="false" style="width: 72px" />
                <span class="mono">{{ row.slotPercent.toFixed(1) }}%</span>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <div v-if="topology?.transaction" class="panel card-pad gap-bottom">
      <h3 class="section-title">Percolator 2PC 分布式事务实时指标（# Transaction）</h3>
      <div class="txn-grid">
        <div class="metric-box">
          <span class="metric-label">累计提交</span>
          <strong class="metric-val mono">{{ topology.transaction.committedTxn }}</strong>
        </div>
        <div class="metric-box">
          <span class="metric-label">活跃事务</span>
          <strong class="metric-val mono">{{ topology.transaction.activeTxn }}</strong>
        </div>
        <div class="metric-box">
          <span class="metric-label">冲突 / 回滚</span>
          <strong class="metric-val mono">{{ topology.transaction.conflictTxn }} / {{ topology.transaction.rollbackTxn }}</strong>
        </div>
        <div class="metric-box">
          <span class="metric-label">平均提交延迟</span>
          <strong class="metric-val mono">{{ topology.transaction.commitLatencyMs.toFixed(3) }} ms</strong>
        </div>
        <div class="metric-box">
          <span class="metric-label">平均 Prewrite 延迟</span>
          <strong class="metric-val mono">{{ topology.transaction.prepareLatencyMs.toFixed(3) }} ms</strong>
        </div>
        <div class="metric-box">
          <span class="metric-label">锁持有 / 网络重试</span>
          <strong class="metric-val mono">{{ topology.transaction.lockCount }} / {{ topology.transaction.networkRetry }}</strong>
        </div>
      </div>
    </div>

    <div class="panel">
      <PageToolbar
        :meta="
          k8sView?.enabled
            ? `Kubernetes Operator 托管集群（命名空间 ${k8sView.namespace} · tieringkv.io/v1）`
            : 'Kubernetes Operator 桥接（纯 JDK HttpClient 直连，未启用时自动降级）'
        "
      >
        <StatusBadge
          :label="k8sView?.enabled ? 'K8s 已接入' : '未启用桥接'"
          :tone="k8sView?.enabled ? 'success' : 'neutral'"
          dot
        />
      </PageToolbar>

      <div v-if="k8sView && !k8sView.enabled" class="k8s-disabled">
        <el-alert
          type="info"
          show-icon
          :closable="false"
          :title="k8sView.message || '当前控制面未开启 Kubernetes Operator 桥接'"
          description="部署在 Kubernetes 中时，配置环境变量 CONSOLE_K8S_ENABLED=true 并为控制台 Pod 绑定 TieringKVCluster RBAC 权限，即可在页面直接完成扩缩容、镜像滚动升级与按需备份。"
        />
      </div>

      <el-table v-else :data="k8sView?.clusters ?? []" size="small">
        <template #empty>
          <EmptyState title="当前命名空间暂无 TieringKVCluster CR" description="可使用 kubectl apply -f deploy/operator/kind-cluster-sample.yaml 创建集群。" />
        </template>
        <el-table-column prop="name" label="集群名称" min-width="130">
          <template #default="{ row }"><span class="mono">{{ row.name }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <StatusBadge
              :label="row.phase"
              :tone="row.phase === 'READY' ? 'success' : row.phase === 'FAILED' ? 'danger' : 'warn'"
              dot
              mono
            />
          </template>
        </el-table-column>
        <el-table-column label="副本就绪 (Meta / Storage / GW)" width="200">
          <template #default="{ row }">
            <span class="mono">
              {{ row.readyMetadata }}/{{ row.metadataReplicas }} ·
              {{ row.readyStorage }}/{{ row.storageReplicas }} ·
              {{ row.readyGateway }}/{{ row.gatewayReplicas }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="image" label="运行镜像" min-width="210">
          <template #default="{ row }"><span class="mono">{{ row.image || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="最近备份" width="150">
          <template #default="{ row }">
            <StatusBadge
              v-if="row.lastBackupStatus"
              :label="row.lastBackupStatus"
              :tone="row.lastBackupStatus === 'SUCCEEDED' ? 'success' : 'warn'"
              mono
            />
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="云原生操作" width="240">
          <template #default="{ row }">
            <el-button v-if="auth.isAdmin" link type="primary" size="small" @click="openScale(row as K8sClusterSummary)">
              <el-icon><Setting /></el-icon> 扩缩容
            </el-button>
            <el-button v-if="auth.isAdmin" link type="warning" size="small" @click="openUpgrade(row as K8sClusterSummary)">
              <el-icon><Upload /></el-icon> 滚动升级
            </el-button>
            <el-button
              v-if="auth.canOperate"
              link
              type="primary"
              size="small"
              :loading="backingUpCluster === row.name"
              @click="triggerK8sBackup(row as K8sClusterSummary)"
            >
              <el-icon><Finished /></el-icon> 按需备份
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 在线扩缩容对话框 -->
    <el-dialog v-model="scaleDialogVisible" :title="`在线扩缩容：${scaleForm.name}`" width="440px">
      <el-form label-width="116px" @submit.prevent>
        <el-form-item label="Storage 副本数">
          <el-input-number v-model="scaleForm.storageReplicas" :min="1" :max="32" />
        </el-form-item>
        <el-form-item label="Gateway 副本数">
          <el-input-number v-model="scaleForm.gatewayReplicas" :min="1" :max="16" />
        </el-form-item>
        <el-form-item label="Metadata 副本数">
          <el-input-number v-model="scaleForm.metadataReplicas" :min="1" :max="7" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="scaleDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="scaleSaving" @click="submitScale">确认下发</el-button>
      </template>
    </el-dialog>

    <!-- 滚动升级镜像对话框 -->
    <el-dialog v-model="upgradeDialogVisible" :title="`滚动升级镜像：${upgradeForm.name}`" width="500px">
      <el-form label-width="96px" @submit.prevent>
        <el-form-item label="目标镜像">
          <el-input v-model="upgradeForm.image" placeholder="例如 ghcr.io/holylaw7/tiering-kv:v6.2.0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="upgradeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="upgradeSaving" @click="submitUpgrade">开始滚动升级</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.header-actions {
  display: flex;
  gap: 8px;
}

.gap-bottom {
  margin-bottom: 14px;
}

.topology-grid {
  display: grid;
  grid-template-columns: minmax(320px, 1fr) minmax(320px, 1fr);
  gap: 14px;
}

@media (max-width: 1080px) {
  .topology-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

.card-pad {
  padding: 14px 16px;
}

.section-title {
  margin: 0 0 10px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
}

.pct-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.txn-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 10px;
}

.metric-box {
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--line-soft);
  background: var(--surface-2);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.metric-label {
  font-size: var(--text-xs);
  color: var(--text-3);
}

.metric-val {
  font-size: 15px;
  color: var(--text);
}

.k8s-disabled {
  padding: 14px 16px;
}

.muted {
  color: var(--text-3);
  font-size: var(--text-xs);
}
</style>
