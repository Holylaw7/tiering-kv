<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus'
import { alertApi } from '@/api'
import type { AlertEvent, AlertRule } from '@/api/types'
import { useInstanceStore } from '@/stores/instance'
import { useAuthStore } from '@/stores/auth'

const store = useInstanceStore()
const auth = useAuthStore()
const tab = ref('events')
const rules = ref<AlertRule[]>([])
const events = ref<AlertEvent[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const status = ref('')
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()

const metrics = [
  { value: 'qps', label: 'QPS', unit: '' },
  { value: 'avg_latency_ms', label: '平均延迟', unit: 'ms' },
  { value: 'used_memory_ratio', label: '内存水位', unit: '0-1' },
  { value: 'connections', label: '连接数', unit: '' },
  { value: 'errors', label: '错误数', unit: '' },
  { value: 'total_keys', label: '键总数', unit: '' }
]

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

async function loadRules() {
  rules.value = await alertApi.rules()
}

async function loadEvents() {
  const result = await alertApi.events({ status: status.value || undefined, page: page.value, size: size.value })
  events.value = result.items
  total.value = result.total
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
  await Promise.all([loadRules(), loadEvents()])
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">告警中心</h2>
        <p class="page-subtitle">基于采集指标做阈值判定，持续满足时长后才触发，恢复后自动闭环</p>
      </div>
      <el-button v-if="auth.canOperate" type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon> 新建规则
      </el-button>
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
          <el-table :data="events" size="small">
            <el-table-column prop="ruleName" label="规则" min-width="140" />
            <el-table-column label="级别" width="90">
              <template #default="{ row }">
                <el-tag :type="levelType(row.level)" size="small" effect="dark">{{ row.level }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="metric" label="指标" width="150" />
            <el-table-column label="观测值" width="110">
              <template #default="{ row }">
                <span class="mono">{{ Number(row.value).toFixed(3) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="阈值" width="100">
              <template #default="{ row }">
                <span class="mono">{{ row.threshold }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="message" label="描述" min-width="240" show-overflow-tooltip />
            <el-table-column prop="firedAt" label="触发时间" width="170" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="row.status === 'FIRING' ? 'danger' : 'success'" size="small">
                  {{ row.status === 'FIRING' ? '触发中' : '已恢复' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="row.status === 'FIRING' && auth.canOperate"
                  link type="primary" size="small" @click="resolve(row)"
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
          <el-table :data="rules" size="small">
            <el-table-column prop="name" label="规则名称" min-width="150" />
            <el-table-column label="作用范围" width="150">
              <template #default="{ row }">
                {{ row.instanceId ? (`实例 #${row.instanceId}`) : '全部实例' }}
              </template>
            </el-table-column>
            <el-table-column prop="metric" label="指标" width="150" />
            <el-table-column label="条件" width="140">
              <template #default="{ row }">
                <span class="mono">{{ row.metric }} {{ row.operator === 'GT' ? '>' : '<' }} {{ row.threshold }}</span>
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
            <el-table-column label="操作" width="140" fixed="right">
              <template #default="{ row }">
                <el-button v-if="auth.canOperate" link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
                <el-button v-if="auth.canOperate" link type="danger" size="small" @click="removeRule(row)">删除</el-button>
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
            <el-select v-model="form.operator" style="width: 100px">
              <el-option value="GT" label="大于" />
              <el-option value="LT" label="小于" />
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
