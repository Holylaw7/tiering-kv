<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { DataAnalysis, Search } from '@element-plus/icons-vue'
import { keyspaceApi } from '@/api'
import type { KeyDetail, KeyEntry, KeyspaceAnalysisView } from '@/api/types'
import { useInstanceStore } from '@/stores/instance'
import { useAuthStore } from '@/stores/auth'
import { formatTtl } from '@/utils/format'
import { buildPrefixChartOption, buildTtlChartOption, formatBigKeySize } from '@/utils/keyspaceAnalyzer'
import ChartPanel from '@/components/ChartPanel.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatCard from '@/components/StatCard.vue'
import StatusBadge, { type BadgeTone } from '@/components/StatusBadge.vue'
import PageToolbar from '@/components/PageToolbar.vue'

function typeTone(type: string): BadgeTone {
  switch (type.toLowerCase()) {
    case 'string':
      return 'accent'
    case 'hash':
      return 'warn'
    case 'list':
    case 'set':
    case 'zset':
      return 'cache'
    default:
      return 'neutral'
  }
}

const store = useInstanceStore()
const auth = useAuthStore()
const pattern = ref('*')
const rows = ref<KeyEntry[]>([])
const loading = ref(false)
const cursor = ref('0')
const finished = ref(true)
const count = ref(50)
const drawerVisible = ref(false)
const detail = ref<KeyDetail>()
const detailLoading = ref(false)
const editingValue = ref('')
const ttlInput = ref<number>(-1)
const hashRows = ref<Array<{ field: string; value: string }>>([])
const newField = ref({ field: '', value: '' })
const hashSaving = ref(false)

// 键空间诊断（v6.4.0 D2）
const activeTab = ref<'browse' | 'analyze'>('browse')
const analyzing = ref(false)
const analyzeMaxKeys = ref(2000)
const analyzeDelimiter = ref(':')
const analysis = ref<KeyspaceAnalysisView | null>(null)

const prefixChartOption = computed(() =>
  buildPrefixChartOption(analysis.value?.topPrefixes ?? [])
)
const ttlChartOption = computed(() =>
  buildTtlChartOption(
    analysis.value?.ttlDistribution ?? { noExpire: 0, under1h: 0, between1hAnd24h: 0, over24h: 0 }
  )
)

async function runAnalysis() {
  const id = store.selectedId
  if (!id) return
  analyzing.value = true
  try {
    analysis.value = await keyspaceApi.analyze(id, {
      pattern: pattern.value || '*',
      maxKeys: analyzeMaxKeys.value,
      delimiter: analyzeDelimiter.value || ':',
      topN: 20
    })
  } finally {
    analyzing.value = false
  }
}

async function scan(reset = true) {
  const id = store.selectedId
  if (!id) return
  loading.value = true
  try {
    const page = await keyspaceApi.scan({
      instanceId: id,
      cursor: reset ? '0' : cursor.value,
      pattern: pattern.value || '*',
      count: count.value
    })
    cursor.value = page.cursor
    finished.value = page.finished
    rows.value = reset ? page.keys : [...rows.value, ...page.keys]
  } finally {
    loading.value = false
  }
}

async function openDetail(row: KeyEntry) {
  const id = store.selectedId
  if (!id) return
  drawerVisible.value = true
  detailLoading.value = true
  try {
    const data = await keyspaceApi.detail(id, row.key)
    detail.value = data
    ttlInput.value = data.ttl
    editingValue.value = typeof data.value === 'string' ? data.value : ''
    hashRows.value =
      data.type === 'hash' && data.value && typeof data.value === 'object'
        ? Object.entries(data.value as Record<string, string>).map(([field, value]) => ({
            field,
            value: String(value)
          }))
        : []
    newField.value = { field: '', value: '' }
  } finally {
    detailLoading.value = false
  }
}

async function saveValue() {
  if (!detail.value) return
  await keyspaceApi.put(store.selectedId, detail.value.key, editingValue.value)
  ElMessage.success('已写入')
  await openDetail(detail.value as KeyEntry)
  await scan(true)
}

async function saveHashField(field: string, value: string) {
  if (!detail.value) return
  if (!field) {
    ElMessage.warning('字段名不能为空')
    return
  }
  hashSaving.value = true
  try {
    await keyspaceApi.putHashField(store.selectedId, detail.value.key, field, value)
    ElMessage.success('字段已保存')
    await openDetail(detail.value as KeyEntry)
    await scan(true)
  } finally {
    hashSaving.value = false
  }
}

async function addHashField() {
  if (!newField.value.field) {
    ElMessage.warning('请填写字段名')
    return
  }
  await saveHashField(newField.value.field, newField.value.value)
}

async function removeHashField(field: string) {
  if (!detail.value) return
  await ElMessageBox.confirm(`确认删除字段 ${field}？`, '危险操作', { type: 'warning' })
  await keyspaceApi.removeHashField(store.selectedId, detail.value.key, field)
  ElMessage.success('字段已删除')
  await openDetail(detail.value as KeyEntry)
  await scan(true)
}

async function saveTtl() {
  if (!detail.value) return
  await keyspaceApi.ttl(store.selectedId, detail.value.key, ttlInput.value)
  ElMessage.success('过期时间已更新')
  await openDetail(detail.value as KeyEntry)
}

async function remove(row: KeyEntry) {
  await ElMessageBox.confirm(`确认删除键 ${row.key}？`, '危险操作', { type: 'warning' })
  await keyspaceApi.remove(store.selectedId, row.key)
  ElMessage.success('已删除')
  await scan(true)
}

function renderValue(): string {
  if (!detail.value) return ''
  const value = detail.value.value
  if (typeof value === 'string') return value
  return JSON.stringify(value, null, 2)
}

watch(
  () => store.selectedId,
  () => {
    analysis.value = null
    scan(true)
  }
)
onMounted(() => scan(true))
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">键空间浏览</h2>
        <p class="page-subtitle">基于 SCAN 游标遍历与批量 Pipeline 采样，不阻塞内核事件循环；支持大键、命名空间前缀与 TTL 健康度诊断</p>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="ks-tabs">
      <el-tab-pane label="键浏览（SCAN）" name="browse">
        <div class="panel" v-loading="loading">
          <PageToolbar meta="SCAN 游标遍历，单次最多 200 条">
            <el-input v-model="pattern" class="f-pattern mono" placeholder="匹配模式，如 user:*" clearable @keyup.enter="scan(true)">
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
            <el-select v-model="count" class="f-count">
              <el-option :value="20" label="20 条/页" />
              <el-option :value="50" label="50 条/页" />
              <el-option :value="100" label="100 条/页" />
            </el-select>
            <el-button type="primary" size="small" @click="scan(true)">扫描</el-button>
          </PageToolbar>
          <el-table :data="rows" size="small">
            <template #empty>
              <EmptyState title="没有扫描到键" description="调整匹配模式后重新扫描。" />
            </template>
            <el-table-column prop="key" label="键" min-width="260" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="mono">{{ row.key }}</span>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="110">
              <template #default="{ row }">
                <StatusBadge :label="row.type.toUpperCase()" :tone="typeTone(row.type)" mono />
              </template>
            </el-table-column>
            <el-table-column label="过期" width="120">
              <template #default="{ row }">
                <span :class="{ mono: true, muted: row.ttl === -1 }">{{ formatTtl(row.ttl) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="170">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openDetail(row as KeyEntry)">查看</el-button>
                <el-button v-if="auth.canOperate" link type="danger" size="small" @click="remove(row as KeyEntry)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="footer">
            <span class="muted">已加载 {{ rows.length }} 个键{{ finished ? '（已扫描完成）' : '' }}</span>
            <div>
              <el-button size="small" :disabled="finished" @click="scan(false)">继续扫描</el-button>
              <el-button size="small" @click="scan(true)">重新扫描</el-button>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="大键 / 前缀 / TTL 诊断" name="analyze">
        <div class="panel" v-loading="analyzing">
          <PageToolbar meta="只读有界采样（100 键批量 Pipeline），对在线业务零阻塞">
            <el-input v-model="pattern" class="f-pattern mono" placeholder="匹配模式，如 *" clearable />
            <el-input v-model="analyzeDelimiter" style="width: 96px" class="mono" placeholder="分隔符 :" />
            <el-select v-model="analyzeMaxKeys" style="width: 130px">
              <el-option :value="500" label="采样 500 键" />
              <el-option :value="2000" label="采样 2,000 键" />
              <el-option :value="5000" label="采样 5,000 键" />
              <el-option :value="10000" label="采样 10,000 键" />
            </el-select>
            <el-button type="primary" size="small" :loading="analyzing" @click="runAnalysis">
              <el-icon><DataAnalysis /></el-icon> 运行诊断
            </el-button>
          </PageToolbar>

          <EmptyState
            v-if="!analysis"
            title="尚未运行键空间诊断"
            description="点击「运行诊断」分析当前实例的 Top-20 大键（BigKeys）、业务前缀占比与 TTL 健康度分布。"
          />

          <template v-else>
            <div class="kpi-grid" style="margin-top: 12px">
              <StatCard
                label="本次采样键数"
                :value="analysis.scannedKeys"
                :hint="`DBSIZE 总计 ${analysis.totalDbSize}${analysis.truncated ? '（已达采样上限）' : ''}`"
              />
              <StatCard
                label="永久未设 TTL"
                :value="analysis.ttlDistribution.noExpire"
                :tone="analysis.scannedKeys > 0 && analysis.ttlDistribution.noExpire / analysis.scannedKeys >= 0.7 ? 'warn' : 'default'"
                :hint="analysis.scannedKeys > 0 ? `占比 ${((analysis.ttlDistribution.noExpire * 100) / analysis.scannedKeys).toFixed(1)}%` : '—'"
              />
              <StatCard
                label="命名空间前缀"
                :value="analysis.topPrefixes.length"
                :hint="analysis.topPrefixes[0] ? `首位 ${analysis.topPrefixes[0].prefix} (${analysis.topPrefixes[0].sharePercent}%)` : '—'"
              />
              <StatCard
                label="诊断耗时"
                :value="`${analysis.durationMs} ms`"
                :hint="`分隔符 '${analysis.delimiter}'`"
              />
            </div>

            <div class="recommend-box" v-if="analysis.recommendations.length > 0">
              <div class="section-label">诊断建议</div>
              <ul class="recommend-list">
                <li v-for="(tip, idx) in analysis.recommendations" :key="idx">{{ tip }}</li>
              </ul>
            </div>

            <div class="type-strip" v-if="Object.keys(analysis.typeDistribution).length > 0">
              <span class="section-label">数据类型分布：</span>
              <StatusBadge
                v-for="(cnt, typeName) in analysis.typeDistribution"
                :key="typeName"
                :label="`${String(typeName).toUpperCase()}: ${cnt}`"
                :tone="typeTone(String(typeName))"
                mono
              />
            </div>

            <div class="chart-grid">
              <ChartPanel title="Top 命名空间前缀分布" hint="按采样键数降序" :option="prefixChartOption" height="240px" />
              <ChartPanel title="TTL 过期健康度分桶" hint="识别未设过期时间的永久键" :option="ttlChartOption" height="240px" />
            </div>

            <div class="bigkey-section">
              <div class="section-label">Top-{{ analysis.bigKeys.length }} 大键（BigKeys）榜单</div>
              <el-table :data="analysis.bigKeys" size="small">
                <el-table-column prop="key" label="键名" min-width="260" show-overflow-tooltip>
                  <template #default="{ row }">
                    <span class="mono">{{ row.key }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="类型" width="108">
                  <template #default="{ row }">
                    <StatusBadge :label="row.type.toUpperCase()" :tone="typeTone(row.type)" mono />
                  </template>
                </el-table-column>
                <el-table-column label="大小 / 元素基数" width="150" align="right">
                  <template #default="{ row }">
                    <span class="num mono">{{ formatBigKeySize(row.sizeUnits, row.unitLabel) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="TTL" width="120">
                  <template #default="{ row }">
                    <span :class="{ mono: true, muted: row.ttl === -1 }">{{ formatTtl(row.ttl) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="100">
                  <template #default="{ row }">
                    <el-button
                      link
                      type="primary"
                      size="small"
                      @click="openDetail({ key: row.key, type: row.type, ttl: row.ttl })"
                    >
                      查看详情
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </template>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="drawerVisible" size="560px" :title="detail?.key ?? '键详情'">
      <div v-loading="detailLoading" class="detail">
        <div class="meta">
          <div class="meta-item">
            <span class="meta-label">类型</span>
            <span class="meta-value mono">{{ detail?.type ?? '—' }}</span>
          </div>
          <div class="meta-item">
            <span class="meta-label">过期</span>
            <span class="meta-value">{{ formatTtl(detail?.ttl) }}</span>
          </div>
        </div>

        <section class="block">
          <header class="block-head">
            <h4>值</h4>
            <span class="block-hint">{{ detail?.type === 'string' ? '可直接编辑并保存' : '按类型只读展示' }}</span>
          </header>

          <template v-if="detail?.type === 'string' && auth.canOperate">
            <el-input v-model="editingValue" type="textarea" :rows="9" class="mono value-editor" />
            <div class="block-actions">
              <el-button type="primary" size="small" @click="saveValue">保存值</el-button>
            </div>
          </template>

          <template v-else-if="detail?.type === 'hash' && auth.canOperate">
            <el-table :data="hashRows" size="small" max-height="300">
              <el-table-column prop="field" label="字段" width="150" show-overflow-tooltip>
                <template #default="{ row }"><span class="mono">{{ row.field }}</span></template>
              </el-table-column>
              <el-table-column label="值">
                <template #default="{ row }">
                  <el-input v-model="row.value" size="small" class="mono" />
                </template>
              </el-table-column>
              <el-table-column label="操作" width="120" align="right">
                <template #default="{ row }">
                  <el-button link size="small" :loading="hashSaving" @click="saveHashField(row.field, row.value)">保存</el-button>
                  <el-button link type="danger" size="small" @click="removeHashField(row.field)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div class="hash-add">
              <el-input v-model="newField.field" size="small" placeholder="新字段" class="mono f-name" />
              <el-input v-model="newField.value" size="small" placeholder="值" class="mono" />
              <el-button size="small" :loading="hashSaving" @click="addHashField">新增字段</el-button>
            </div>
          </template>

          <pre v-else class="value-box mono">{{ renderValue() }}</pre>
        </section>

        <section class="block">
          <header class="block-head">
            <h4>过期时间</h4>
            <span class="block-hint">单位秒，-1 表示永久</span>
          </header>
          <div class="ttl-row">
            <el-input-number v-model="ttlInput" :min="-1" controls-position="right" :disabled="!auth.canOperate" />
            <el-button v-if="auth.canOperate" size="small" @click="saveTtl">应用</el-button>
          </div>
        </section>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.detail {
  display: flex;
  flex-direction: column;
  gap: 22px;
}

.meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface-2);
}

.meta-item {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 10px 14px;
}

.meta-item + .meta-item {
  border-left: 1px solid var(--line);
}

.meta-label {
  font-size: var(--text-xs);
  font-weight: 600;
  letter-spacing: 0.05em;
  text-transform: uppercase;
  color: var(--text-3);
}

.meta-value {
  font-size: var(--text-sm);
  color: var(--text-1);
}

.block-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 8px;
}

.block-head h4 {
  margin: 0;
  font-size: var(--text-base);
  font-weight: 600;
  color: var(--text);
}

.block-hint {
  font-size: var(--text-xs);
  color: var(--text-3);
}

.value-editor :deep(.el-textarea__inner) {
  font-size: 12.5px;
  line-height: 1.6;
}

.block-actions {
  margin-top: 10px;
  display: flex;
  justify-content: flex-end;
}

.ttl-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.hash-add {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 10px;
}

.f-name {
  width: 150px;
}

.f-pattern {
  width: 240px;
}

.f-count {
  width: 108px;
}

.footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
}

.muted {
  color: var(--text-2);
  font-size: 12px;
}

.value-box {
  background: var(--term-bg);
  color: var(--term-text);
  border: 1px solid var(--term-line);
  border-radius: var(--radius);
  padding: 12px;
  max-height: 320px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  margin: 0;
}

.chart-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  margin-top: 14px;
}

.recommend-box {
  margin-top: 14px;
  padding: 10px 14px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface-2);
}

.recommend-list {
  margin: 6px 0 0;
  padding-left: 18px;
  font-size: var(--text-sm);
  color: var(--text-2);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.type-strip {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
}

.bigkey-section {
  margin-top: 16px;
}

.section-label {
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--text-3);
  margin-bottom: 6px;
}

@media (max-width: 960px) {
  .chart-grid {
    grid-template-columns: 1fr;
  }
}
</style>