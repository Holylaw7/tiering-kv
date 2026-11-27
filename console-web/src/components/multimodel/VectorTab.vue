<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Finished, Plus, Search } from '@element-plus/icons-vue'
import { multiModelApi } from '@/api'
import type { VectorCollectionsView, VectorSearchResultView } from '@/api/types'
import { normalizeSimilarityPercent, parseVectorText } from '@/utils/multimodel'
import EmptyState from '@/components/EmptyState.vue'
import PageToolbar from '@/components/PageToolbar.vue'

const props = defineProps<{
  instanceId?: number
  vectorInfo: VectorCollectionsView | null
  canOperate: boolean
}>()

const emit = defineEmits<{
  (e: 'refresh'): void
}>()

const selectedCollection = ref('default')
const vectorQueryText = ref('0.1, 0.2, 0.3, 0.4')
const vectorTopK = ref(10)
const vectorSearching = ref(false)
const vectorResult = ref<VectorSearchResultView | null>(null)
const vectorCheckpointing = ref(false)

const addVectorVisible = ref(false)
const addVectorSaving = ref(false)
const addVectorForm = ref({
  collection: 'default',
  id: '',
  valuesText: '0.1, 0.2, 0.3, 0.4'
})

watch(
  () => props.vectorInfo,
  (vec) => {
    if (vec?.supported && vec.collections.length > 0) {
      const exists = vec.collections.some((c) => c.name === selectedCollection.value)
      if (!exists) {
        selectedCollection.value = vec.collections[0].name
      }
    }
  },
  { immediate: true }
)

watch(
  () => props.instanceId,
  () => {
    vectorResult.value = null
  }
)

function openAddVector() {
  addVectorForm.value = {
    collection: selectedCollection.value || 'default',
    id: '',
    valuesText: vectorQueryText.value || '0.1, 0.2, 0.3, 0.4'
  }
  addVectorVisible.value = true
}

async function submitAddVector() {
  if (!props.instanceId) return
  const f = addVectorForm.value
  if (!f.id.trim()) {
    ElMessage.warning('请填写向量 ID')
    return
  }
  let values: number[]
  try {
    values = parseVectorText(f.valuesText)
  } catch (err: unknown) {
    ElMessage.warning(err instanceof Error ? err.message : '向量格式无效')
    return
  }
  addVectorSaving.value = true
  try {
    const col = f.collection.trim() || 'default'
    await multiModelApi.addVector({
      instanceId: props.instanceId,
      collection: col,
      id: f.id.trim(),
      values
    })
    ElMessage.success(`向量 ${f.id.trim()} 已写入集合 ${col}`)
    selectedCollection.value = col
    addVectorVisible.value = false
    emit('refresh')
  } finally {
    addVectorSaving.value = false
  }
}

async function runVectorSearch() {
  if (!props.instanceId) {
    ElMessage.warning('请先选择实例')
    return
  }
  let vector: number[]
  try {
    vector = parseVectorText(vectorQueryText.value)
  } catch (err: unknown) {
    ElMessage.warning(err instanceof Error ? err.message : '查询向量格式无效')
    return
  }
  vectorSearching.value = true
  try {
    vectorResult.value = await multiModelApi.searchVector({
      instanceId: props.instanceId,
      collection: selectedCollection.value.trim() || 'default',
      vector,
      topK: vectorTopK.value
    })
  } finally {
    vectorSearching.value = false
  }
}

async function deleteVectorItem(id: string) {
  if (!props.instanceId) return
  await ElMessageBox.confirm(`确认删除向量「${id}」？`, '删除向量', { type: 'warning' })
  await multiModelApi.deleteVector(props.instanceId, selectedCollection.value, id)
  ElMessage.success('向量已删除')
  emit('refresh')
  await runVectorSearch()
}

async function dropCollection(name: string) {
  if (!props.instanceId) return
  await ElMessageBox.confirm(`确认删除整个向量集合「${name}」？该操作不可恢复。`, '删除集合', {
    type: 'warning'
  })
  await multiModelApi.dropVectorCollection(props.instanceId, name)
  ElMessage.success(`集合 ${name} 已删除`)
  vectorResult.value = null
  emit('refresh')
}

async function checkpointCollection() {
  if (!props.instanceId) return
  vectorCheckpointing.value = true
  try {
    await multiModelApi.checkpointVector(props.instanceId, selectedCollection.value || undefined)
    ElMessage.success(`集合 ${selectedCollection.value || '全部'} 物理索引落盘完成`)
  } finally {
    vectorCheckpointing.value = false
  }
}
</script>

<template>
  <div>
    <PageToolbar :meta="`共 ${vectorInfo?.collections.length ?? 0} 个集合 · ${vectorInfo?.totalVectors ?? 0} 条向量`">
      <el-select
        v-model="selectedCollection"
        placeholder="选择集合"
        filterable
        allow-create
        style="width: 180px"
      >
        <el-option
          v-for="col in vectorInfo?.collections ?? []"
          :key="col.name"
          :label="`${col.name} (${col.count})`"
          :value="col.name"
        />
      </el-select>
      <el-button v-if="canOperate" type="primary" :disabled="vectorInfo?.supported === false" @click="openAddVector">
        <el-icon><Plus /></el-icon> 写入向量
      </el-button>
      <el-button
        v-if="canOperate"
        :loading="vectorCheckpointing"
        :disabled="vectorInfo?.supported === false"
        @click="checkpointCollection"
      >
        <el-icon><Finished /></el-icon> 索引落盘
      </el-button>
    </PageToolbar>

    <div class="split-grid">
      <div class="sub-card">
        <h4 class="sub-title">集合清单</h4>
        <el-table :data="vectorInfo?.collections ?? []" size="small">
          <template #empty>
            <EmptyState title="暂无向量集合" description="点击「写入向量」自动创建默认或自定义集合。" />
          </template>
          <el-table-column prop="name" label="集合名称">
            <template #default="{ row }">
              <el-button link type="primary" @click="selectedCollection = row.name">
                <span class="mono">{{ row.name }}</span>
              </el-button>
            </template>
          </el-table-column>
          <el-table-column prop="count" label="向量数" width="88" align="right">
            <template #default="{ row }"><span class="num">{{ row.count }}</span></template>
          </el-table-column>
          <el-table-column v-if="canOperate" label="操作" width="76">
            <template #default="{ row }">
              <el-button link type="danger" size="small" @click="dropCollection(row.name)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="sub-card">
        <h4 class="sub-title">Top-K 相似度检索（VECTOR.SEARCH）</h4>
        <div class="query-bar">
          <el-input
            v-model="vectorQueryText"
            placeholder="输入查询向量，例如 0.1, 0.2, 0.3, 0.4"
            class="flex-1"
          />
          <el-input-number v-model="vectorTopK" :min="1" :max="100" style="width: 116px" />
          <el-button
            type="primary"
            :loading="vectorSearching"
            :disabled="vectorInfo?.supported === false"
            @click="runVectorSearch"
          >
            <el-icon><Search /></el-icon> 检索
          </el-button>
        </div>
        <div v-if="vectorResult" class="result-meta mono">
          集合 {{ vectorResult.collection }} · 命中 {{ vectorResult.hits.length }} 条 · 耗时 {{ vectorResult.costMs }} ms
        </div>
        <el-table :data="vectorResult?.hits ?? []" size="small" style="margin-top: 8px">
          <template #empty>
            <EmptyState title="输入向量并点击检索" description="返回按相似度排序的 Top-K 向量 ID 与分值。" />
          </template>
          <el-table-column type="index" label="#" width="48" />
          <el-table-column prop="id" label="向量 ID" min-width="140">
            <template #default="{ row }"><span class="mono">{{ row.id }}</span></template>
          </el-table-column>
          <el-table-column label="相似度 / 分值" min-width="200">
            <template #default="{ row }">
              <div class="score-cell">
                <el-progress
                  :percentage="normalizeSimilarityPercent(row.score)"
                  :stroke-width="6"
                  :show-text="false"
                  style="width: 110px"
                />
                <span class="mono">{{ Number(row.score).toFixed(4) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column v-if="canOperate" label="操作" width="76">
            <template #default="{ row }">
              <el-button link type="danger" size="small" @click="deleteVectorItem(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <el-dialog v-model="addVectorVisible" title="写入向量（VECTOR.ADD）" width="480px">
      <el-form label-width="92px" @submit.prevent>
        <el-form-item label="集合名称">
          <el-input v-model="addVectorForm.collection" placeholder="default" />
        </el-form-item>
        <el-form-item label="向量 ID">
          <el-input v-model="addVectorForm.id" placeholder="例如 doc-001" />
        </el-form-item>
        <el-form-item label="向量分量">
          <el-input
            v-model="addVectorForm.valuesText"
            type="textarea"
            :rows="4"
            placeholder="逗号或空格分隔浮点数，如 0.1, 0.2, 0.3, 0.4"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addVectorVisible = false">取消</el-button>
        <el-button type="primary" :loading="addVectorSaving" @click="submitAddVector">写入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.split-grid {
  display: grid;
  grid-template-columns: minmax(260px, 340px) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

@media (max-width: 1024px) {
  .split-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

.sub-card {
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
  background: var(--surface-2);
  padding: 14px;
}

.sub-title {
  margin: 0 0 10px;
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text-1);
}

.query-bar {
  display: flex;
  align-items: center;
  gap: 8px;
}

.flex-1 {
  flex: 1;
  min-width: 140px;
}

.result-meta {
  margin-top: 8px;
  font-size: var(--text-xs);
  color: var(--text-3);
}

.score-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}
</style>
