<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { terminalApi } from '@/api'
import type { TerminalHistory } from '@/api/types'
import { useInstanceStore } from '@/stores/instance'

interface Entry {
  command: string
  payload: unknown
  costMs: number
  error: boolean
  at: string
}

const store = useInstanceStore()
const command = ref('')
const entries = ref<Entry[]>([])
const history = ref<TerminalHistory[]>([])
const busy = ref(false)
const outputRef = ref<HTMLDivElement | null>(null)

const quickCommands = ['PING', 'INFO', 'DBSIZE', 'SCAN 0 COUNT 20', 'TYPE demo', 'CLIENT LIST']

async function execute(raw?: string) {
  const text = (raw ?? command.value).trim()
  if (!text || !store.selectedId) return
  busy.value = true
  try {
    const result = await terminalApi.execute(store.selectedId, text)
    entries.value.push({
      command: text,
      payload: result.payload,
      costMs: result.costMs,
      error: result.error,
      at: new Date().toLocaleTimeString('zh-CN')
    })
    command.value = ''
    await loadHistory()
  } finally {
    busy.value = false
    await nextTick()
    if (outputRef.value) outputRef.value.scrollTop = outputRef.value.scrollHeight
  }
}

function clear() {
  entries.value = []
}

async function loadHistory() {
  const page = await terminalApi.history(1, 12)
  history.value = page.items
}

function render(payload: unknown): string {
  if (payload === null || payload === undefined) return '(nil)'
  if (typeof payload === 'string') return payload
  return JSON.stringify(payload, null, 2)
}

function useHistory(item: TerminalHistory) {
  command.value = item.command
}

onMounted(loadHistory)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">在线终端</h2>
        <p class="page-subtitle">直连实例执行原始命令；危险命令由平台策略拦截并全程审计</p>
      </div>
      <el-button size="small" @click="clear">清屏</el-button>
    </div>

    <div class="wrap">
      <div class="panel term">
        <div ref="outputRef" class="output mono">
          <div v-if="!entries.length" class="empty">
            <p>输入命令开始会话，例如 <code>SET demo hello</code>、<code>INFO memory</code></p>
          </div>
          <div v-for="(entry, index) in entries" :key="index" class="entry">
            <div class="cmd">
              <span class="prompt">{{ store.selected?.host }}:{{ store.selected?.port }}&gt;</span>
              <span>{{ entry.command }}</span>
              <span class="cost">{{ entry.costMs }} ms</span>
            </div>
            <pre :class="['reply', { error: entry.error }]">{{ render(entry.payload) }}</pre>
          </div>
        </div>
        <div class="input-row">
          <span class="prompt mono">&gt;</span>
          <el-input
            v-model="command"
            class="mono"
            placeholder="输入命令后回车执行"
            :disabled="busy"
            @keyup.enter="execute()"
          />
          <el-button type="primary" :loading="busy" @click="execute()">执行</el-button>
        </div>
        <div class="quick">
          <el-tag
            v-for="item in quickCommands"
            :key="item"
            class="chip mono"
            size="small"
            @click="execute(item)"
          >
            {{ item }}
          </el-tag>
        </div>
      </div>

      <div class="panel side">
        <div class="panel-title">最近命令</div>
        <el-scrollbar height="520px">
          <div
            v-for="item in history"
            :key="item.id"
            class="history-item"
            @click="useHistory(item)"
          >
            <div class="history-cmd mono">{{ item.command }}</div>
            <div class="history-meta">
              <el-tag :type="item.success ? 'success' : 'danger'" size="small" effect="plain">
                {{ item.success ? 'OK' : 'ERR' }}
              </el-tag>
              <span>{{ item.costMs }} ms</span>
              <span>{{ item.createdAt?.slice(11) }}</span>
            </div>
          </div>
          <el-empty v-if="!history.length" description="暂无命令记录" :image-size="70" />
        </el-scrollbar>
      </div>
    </div>
  </div>
</template>

<style scoped>
.wrap {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 16px;
}

.term {
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
}

.output {
  height: 520px;
  overflow-y: auto;
  padding: 16px 18px;
  background: #0d1218;
  font-size: 12.5px;
  line-height: 1.6;
}

.entry {
  margin-bottom: 14px;
}

.cmd {
  display: flex;
  gap: 8px;
  color: #d7e3ee;
}

.prompt {
  color: #35c98b;
}

.cost {
  margin-left: auto;
  color: #5c6b7a;
  font-size: 11px;
}

.reply {
  margin: 6px 0 0;
  padding: 8px 10px;
  background: rgba(255, 255, 255, 0.03);
  border-left: 2px solid #2a3441;
  white-space: pre-wrap;
  word-break: break-all;
  color: #9fb3c8;
}

.reply.error {
  border-left-color: #f56c6c;
  color: #ff9c9c;
}

.input-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  border-top: 1px solid var(--console-border);
}

.input-row .prompt {
  font-size: 16px;
}

.quick {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 0 16px 14px;
}

.chip {
  cursor: pointer;
}

.side {
  display: flex;
  flex-direction: column;
}

.history-item {
  padding: 10px 8px;
  border-bottom: 1px dashed var(--console-border);
  cursor: pointer;
}

.history-item:hover {
  background: #1a222b;
}

.history-cmd {
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.history-meta {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-top: 6px;
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.empty {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.empty code {
  color: #35c98b;
}
</style>
