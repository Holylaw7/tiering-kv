<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus'
import { Plus, Promotion } from '@element-plus/icons-vue'
import { alertApi } from '@/api'
import type { AlertChannelView, AlertEvent, AlertRule } from '@/api/types'
import { useInstanceStore } from '@/stores/instance'
import { useAuthStore } from '@/stores/auth'
import EmptyState from '@/components/EmptyState.vue'
import StatCard from '@/components/StatCard.vue'
import StatusBadge from '@/components/StatusBadge.vue'

const store = useInstanceStore()
const auth = useAuthStore()
const tab = ref('events')
const rules = ref<AlertRule[]>([])
const events = ref<AlertEvent[]>([])
const channels = ref<AlertChannelView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const status = ref('')
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()

const channelDialogVisible = ref(false)
const editingChannelId = ref<number | null>(null)
const channelFormRef = ref<FormInstance>()
const testingChannelId = ref<number | null>(null)
const channelForm = ref({
  name: '',
  type: 'WEBHOOK',
  url: '',
  secret: '',
  silenceSeconds: 300,
  enabled: true
})

const metrics = [
  { value: 'qps', label: 'QPS', unit: '' },
  { value: 'avg_latency_ms', label: '平均延迟', unit: 'ms' },
  { value: 'latency_p99_ms', label: 'P99 延迟', unit: 'ms' },
  { value: 'used_memory_ratio', label: '内存水位', unit: '0-1' },
  { value: 'tiering_cold_sstable_count', label: '冷层 SSTable 数', unit: '' },
  { value: 'tiering_block_cache_hit_rate', label: 'BlockCache 命中率', unit: '0-1' },
  { value: 'tiering_migration_failed', label: '冷迁移失败数', unit: '' },
  { value: 'connections', label: '连接数', unit: '' },
  { value: 'errors', label: '错误数', unit: '' },
  { value: 'total_keys', label: '键总数', unit: '' }
]

function operatorSymbol(op: string): string {
  switch (op) {
    case 'GT':
      return '>'
    case 'GTE':
      return '≥'
    case 'LT':
      return '<'
    case 'LTE':
      return '≤'
    default:
      return op
  }
}

const form = reactive({
  name: '',
  instanceId: undefined as number | undefined,
  metric: 'avg_latency_ms',
  operator: 'GT',
  threshold: 5,
  durationSeconds: 0,
  level: 'WARN',
  enabled: true
})

const enabledRuleCount = computed(() => rules.value.filter((r) => r.enabled).length)
const firingInPage = computed(() => events.value.filter((e) => e.status === 'FIRING').length)
const critInPage = computed(() => events.value.filter((e) => e.level === 'CRIT').length)

async function loadRules() {
  rules.value = await alertApi.rules()
}

async function loadEvents() {
  const result = await alertApi.events({ status: status.value || undefined, page: page.value, size: size.value })
  events.value = result.items
  total.value = result.total
}

async function loadChannels() {
  channels.value = await alertApi.channels()
}

function openCreateChannel() {
  editingChannelId.value = null
  channelForm.value = {
    name: '',
    type: 'WEBHOOK',
    url: '',
    secret: '',
    silenceSeconds: 300,
    enabled: true
  }
  channelDialogVisible.value = true
}

function openEditChannel(channel: AlertChannelView) {
  editingChannelId.value = channel.id
  channelForm.value = {
    name: channel.name,
    type: channel.type,
    url: channel.url,
    secret: '',
    silenceSeconds: channel.silenceSeconds,
    enabled: channel.enabled
  }
  channelDialogVisible.value = true
}

async function submitChannel() {
  const valid = await channelFormRef.value?.validate().catch(() => false)
  if (!valid) return
  if (editingChannelId.value) {
    await alertApi.updateChannel(editingChannelId.value, { ...channelForm.value })
    ElMessage.success('通知渠道已更新')
  } else {
    await alertApi.createChannel({ ...channelForm.value })
    ElMessage.success('通知渠道已创建')
  }
  channelDialogVisible.value = false
  await loadChannels()
}

async function removeChannel(channel: AlertChannelView) {
  await ElMessageBox.confirm(`确认删除通知渠道 ${channel.name}？`, '提示', { type: 'warning' })
  await alertApi.removeChannel(channel.id)
  ElMessage.success('渠道已删除')
  await loadChannels()
}

async function testChannel(channel: AlertChannelView) {
  testingChannelId.value = channel.id
  try {
    const res = await alertApi.testChannel(channel.id)
    if (res.delivered) {
      ElMessage.success(`推送成功（${res.message}，耗时 ${res.costMs} ms）`)
    } else {
      ElMessage.error(`推送失败：${res.message}`)
    }
  } finally {
    testingChannelId.value = null
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, {
    name: '', instanceId: store.selectedId, metric: 'avg_latency_ms',
    operator: 'GT', threshold: 5, durationSeconds: 0, level: 'WARN', enabled: true
  })
  dialogVisible.value = true
}

function openEdit(rule: AlertRule) {
  editingId.value = rule.id
  Object.assign(form, {
    name: rule.name, instanceId: rule.instanceId, metric: rule.metric,
    operator: rule.operator, threshold: rule.threshold,
    durationSeconds: rule.durationSeconds, level: rule.level, enabled: rule.enabled
  })
  dialogVisible.value = true
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (editingId.value) {
    await alertApi.updateRule(editingId.value, { ...form })
    ElMessage.success('规则已更新')
  } else {
    await alertApi.createRule({ ...form })
    ElMessage.success('规则已创建')
  }
  dialogVisible.value = false
  await loadRules()
}

async function removeRule(rule: AlertRule) {
  await ElMessageBox.confirm(`确认删除规则 ${rule.name}？`, '提示', { type: 'warning' })
  await alertApi.removeRule(rule.id)
  ElMessage.success('已删除')
  await loadRules()
}

async function resolve(event: AlertEvent) {
  await alertApi.resolve(event.id)
  ElMessage.success('已标记恢复')
  await loadEvents()
}

function levelType(level: string) {
  return level === 'CRIT' ? 'danger' : 'warning'
}

onMounted(async () => {
  await Promise.all([loadRules(), loadEvents(), loadChannels()])
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">告警中心</h2>
        <p class="page-subtitle">基于采集指标做阈值判定，持续满足时长后才触发，恢复后自动闭环</p>
      </div>
      <div style="display: flex; gap: 8px">
        <el-button v-if="auth.canOperate" @click="openCreateChannel">
          <el-icon><Promotion /></el-icon> 新建通知渠道
        </el-button>
        <el-button v-if="auth.canOperate" type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon> 新建规则
        </el-button>
      </div>
    </div>

    <div class="kpi-grid">
      <StatCard label="规则总数" :value="rules.length" :hint="`已启用 ${enabledRuleCount} 条`" />
      <StatCard label="累计告警事件" :value="total" hint="按时间倒序分页" />
      <StatCard
        label="当前页触发中"
        :value="firingInPage"
        :tone="firingInPage > 0 ? 'danger' : 'default'"
        hint="FIRING 状态事件"
      />
      <StatCard
        label="当前页严重级别"
        :value="critInPage"
        :tone="critInPage > 0 ? 'warn' : 'default'"
        hint="CRIT 级告警"
      />
    </div>

    <div class="panel">
      <el-tabs v-model="tab">
        <el-tab-pane label="告警事件" name="events">
          <div class="filter">
            <el-select v-model="status" placeholder="全部状态" clearable style="width: 150px" @change="loadEvents">
              <el-option value="FIRING" label="触发中" />
              <el-option value="RESOLVED" label="已恢复" />
            </el-select>
            <el-button size="small" @click="loadEvents">刷新</el-button>
          </div>
          <el-table :data="events" size="small" style="width: 100%">
        <template #empty>
          <EmptyState title="暂无告警事件" description="规则命中阈值后会在这里产生事件。" />
        </template>
            <el-table-column prop="ruleName" label="规则" width="170" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono">{{ row.ruleName }}</span></template>
        </el-table-column>
            <el-table-column label="级别" width="90">
              <template #default="{ row }">
                <StatusBadge :label="row.level" :tone="levelType(row.level) === 'danger' ? 'danger' : 'warn'" mono />
              </template>
            </el-table-column>
            <el-table-column prop="metric" label="指标" width="130" />
            <el-table-column label="观测 / 阈值" width="140">
              <template #default="{ row }">
                <span class="mono num">{{ Number(row.value).toFixed(3) }} / {{ row.threshold }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="message" label="描述" min-width="200" show-overflow-tooltip />
            <el-table-column prop="firedAt" label="触发时间" width="170" />
            <el-table-column label="状态" width="106">
              <template #default="{ row }">
                <StatusBadge
                  :label="row.status === 'FIRING' ? '触发中' : '已恢复'"
                  :tone="row.status === 'FIRING' ? 'danger' : 'success'"
                  dot
                />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110">
              <template #default="{ row }">
                <el-button
                  v-if="row.status === 'FIRING' && auth.canOperate"
                  link type="primary" size="small" @click="resolve(row as AlertEvent)"
                >
                  标记恢复
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            v-model:current-page="page"
            :page-size="size"
            :total="total"
            layout="prev, pager, next, total"
            class="pager"
            @current-change="loadEvents"
          />
        </el-tab-pane>

        <el-tab-pane label="告警规则" name="rules">
          <el-table :data="rules" size="small" style="width: 100%">
        <template #empty>
          <EmptyState title="还没有告警规则" description="创建规则后，系统按采集周期求值，命中即产生事件。" />
        </template>
            <el-table-column prop="name" label="规则名称" min-width="150" />
            <el-table-column label="作用范围" width="150">
              <template #default="{ row }">
                {{ row.instanceId ? (`实例 #${row.instanceId}`) : '全部实例' }}
              </template>
            </el-table-column>
            <el-table-column prop="metric" label="指标" width="150" />
            <el-table-column label="条件" width="140">
              <template #default="{ row }">
                <span class="mono">{{ row.metric }} {{ operatorSymbol(row.operator) }} {{ row.threshold }}</span>
              </template>
            </el-table-column>
            <el-table-column label="持续" width="90">
              <template #default="{ row }">{{ row.durationSeconds }} s</template>
            </el-table-column>
            <el-table-column label="级别" width="90">
              <template #default="{ row }">
                <el-tag :type="levelType(row.level)" size="small">{{ row.level }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="启用" width="80">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '是' : '否' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="140">
              <template #default="{ row }">
                <el-button v-if="auth.canOperate" link type="primary" size="small" @click="openEdit(row as AlertRule)">编辑</el-button>
                <el-button v-if="auth.canOperate" link type="danger" size="small" @click="removeRule(row as AlertRule)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="`通知渠道 (${channels.length})`" name="channels">
          <el-table :data="channels" size="small" style="width: 100%">
            <template #empty>
              <EmptyState title="尚未配置通知渠道" description="新增 Webhook 渠道后，告警触发与恢复将自动异步推送。" />
            </template>
            <el-table-column prop="name" label="渠道名称" width="160" />
            <el-table-column prop="type" label="类型" width="100">
              <template #default="{ row }">
                <StatusBadge :label="row.type" tone="accent" mono />
              </template>
            </el-table-column>
            <el-table-column prop="url" label="Webhook URL" min-width="240" show-overflow-tooltip>
              <template #default="{ row }"><span class="mono">{{ row.url }}</span></template>
            </el-table-column>
            <el-table-column label="签名" width="90">
              <template #default="{ row }">
                <StatusBadge v-if="row.hasSecret" label="HMAC" tone="success" mono />
                <span v-else class="hint">无</span>
              </template>
            </el-table-column>
            <el-table-column label="静默窗口" width="100">
              <template #default="{ row }">{{ row.silenceSeconds }} s</template>
            </el-table-column>
            <el-table-column label="状态" width="84">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="190">
              <template #default="{ row }">
                <el-button
                  v-if="auth.canOperate"
                  link type="success" size="small"
                  :loading="testingChannelId === row.id"
                  @click="testChannel(row as AlertChannelView)"
                >
                  测试发送
                </el-button>
                <el-button v-if="auth.canOperate" link type="primary" size="small" @click="openEditChannel(row as AlertChannelView)">编辑</el-button>
                <el-button v-if="auth.canOperate" link type="danger" size="small" @click="removeChannel(row as AlertChannelView)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑规则' : '新建规则'" width="520px">
      <el-form ref="formRef" :model="form" label-width="100px">
        <el-form-item label="规则名称" prop="name" :rules="[{ required: true, message: '请输入名称', trigger: 'blur' }]">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="作用实例">
          <el-select v-model="form.instanceId" clearable placeholder="全部实例" style="width: 100%">
            <el-option v-for="item in store.list" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="指标">
          <el-select v-model="form.metric" style="width: 100%">
            <el-option v-for="item in metrics" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="条件">
          <div style="display: flex; gap: 10px">
            <el-select v-model="form.operator" style="width: 118px">
              <el-option value="GT" label="大于" />
              <el-option value="GTE" label="大于等于" />
              <el-option value="LT" label="小于" />
              <el-option value="LTE" label="小于等于" />
            </el-select>
            <el-input-number v-model="form.threshold" :step="0.1" controls-position="right" style="flex: 1" />
          </div>
        </el-form-item>
        <el-form-item label="持续时长">
          <el-input-number v-model="form.durationSeconds" :min="0" :max="3600" controls-position="right" />
          <span class="hint">秒（0 表示立即触发）</span>
        </el-form-item>
        <el-form-item label="级别">
          <el-radio-group v-model="form.level">
            <el-radio-button value="WARN">警告</el-radio-button>
            <el-radio-button value="CRIT">严重</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="channelDialogVisible" :title="editingChannelId ? '编辑通知渠道' : '新建通知渠道'" width="520px">
      <el-form ref="channelFormRef" :model="channelForm" label-width="100px">
        <el-form-item label="渠道名称" prop="name" :rules="[{ required: true, message: '请输入渠道名称', trigger: 'blur' }]">
          <el-input v-model="channelForm.name" placeholder="如 运维企微群 / PagerDuty" />
        </el-form-item>
        <el-form-item label="Webhook URL" prop="url" :rules="[{ required: true, message: '请输入 http/https 地址', trigger: 'blur' }]">
          <el-input v-model="channelForm.url" placeholder="https://example.com/webhook/alerts" />
        </el-form-item>
        <el-form-item label="签名密钥">
          <el-input v-model="channelForm.secret" type="password" show-password placeholder="可选：HMAC-SHA256 签名密钥（编辑留空保持原值）" />
        </el-form-item>
        <el-form-item label="静默窗口">
          <el-input-number v-model="channelForm.silenceSeconds" :min="0" :max="86400" controls-position="right" />
          <span class="hint">秒（避免重复触发刷屏，恢复通知不受限）</span>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="channelForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="channelDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitChannel">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.filter {
  display: flex;
  gap: 10px;
  margin-bottom: 12px;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.hint {
  margin-left: 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>