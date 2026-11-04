<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { terminalApi } from '@/api'
import type { TerminalHistory } from '@/api/types'
import { useInstanceStore } from '@/stores/instance'
import EmptyState from '@/components/EmptyState.vue'

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
      <el-button @click="clear">清屏</el-button>
    </div>

    <div class="wrap">
      <section class="panel term">
        <div ref="outputRef" class="output mono">
          <EmptyState
            v-if="!entries.length"
            title="会话尚未开始"
            description="输入一条命令后回车执行；下方快捷命令可直接点击。"
          />
          <div v-for="(entry, index) in entries" :key="index" class="entry">
            <div class="cmd">
              <span class="prompt">{{ store.selected?.host }}:{{ store.selected?.port }}&gt;</span>
              <span class="text">{{ entry.command }}</span>
              <span class="cost">{{ entry.costMs }} ms</span>
            </div>
            <pre :class="['reply', { error: entry.error }]">{{ render(entry.payload) }}</pre>
          </div>
        </div>

        <div class="composer">
          <div class="input-row">
            <span class="caret mono">&gt;</span>
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
            <button
              v-for="item in quickCommands"
              :key="item"
              type="button"
              class="chip mono"
              @click="execute(item)"
            >
              {{ item }}
            </button>
          </div>
        </div>
      </section>

      <section class="panel side">
        <div class="panel-title">
          最近命令
          <span class="hint">点击填入</span>
        </div>
        <div v-if="!history.length" class="side-empty">暂无命令记录</div>
        <ul v-else class="history">
          <li v-for="item in history" :key="item.id" class="history-item" @click="useHistory(item)">
            <span class="status" :class="item.success ? 'ok' : 'err'"></span>
            <span class="cmd mono">{{ item.command }}</span>
            <span class="meta">{{ item.costMs }} ms · {{ item.createdAt?.slice(11) }}</span>
          </li>
        </ul>
      </section>
    </div>
  </div>
</template>

<style scoped>
.wrap {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 14px;
  align-items: start;
}

/* ---------- 终端 ---------- */

.term {
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
}

.output {
  height: 520px;
  overflow-y: auto;
  padding: 14px 18px;
  background: var(--bg-subtle);
  border-bottom: 1px solid var(--line);
  font-size: 12.5px;
  line-height: 1.6;
}

.entry + .entry {
  margin-top: 14px;
}

.cmd {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.prompt {
  color: var(--accent);
  font-size: 12px;
}

.text {
  color: var(--text);
  word-break: break-all;
}

.cost {
  margin-left: auto;
  font-size: 11px;
  color: var(--text-3);
  white-space: nowrap;
}

.reply {
  margin: 6px 0 0;
  padding: 8px 10px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  color: var(--text-2);
  font-size: 12.5px;
  white-space: pre-wrap;
  word-break: break-all;
}

.reply.error {
  color: #f47c78;
  border-color: rgba(240, 84, 79, 0.28);
  background: rgba(240, 84, 79, 0.06);
}

.composer {
  padding: 12px 14px 14px;
  background: var(--surface);
}

.input-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.caret {
  color: var(--accent);
  font-size: 13px;
}

.quick {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.chip {
  padding: 3px 8px;
  border: 1px solid var(--line-strong);
  border-radius: 4px;
  background: transparent;
  color: var(--text-2);
  font-size: 11.5px;
  cursor: pointer;
  transition: background-color 0.14s var(--ease), color 0.14s var(--ease);
}

.chip:hover {
  background: var(--surface-2);
  color: var(--text);
}

/* ---------- 历史 ---------- */

.side {
  min-height: 0;
}

.side-empty {
  padding: 24px 0;
  text-align: center;
  font-size: 12.5px;
  color: var(--text-3);
}

.history {
  list-style: none;
  margin: 0;
  padding: 0;
}

.history-item {
  display: grid;
  grid-template-columns: 8px minmax(0, 1fr);
  grid-template-rows: auto auto;
  column-gap: 8px;
  align-items: center;
  padding: 8px 8px;
  margin: 0 -8px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: background-color 0.14s var(--ease);
}

.history-item:hover {
  background: var(--surface-2);
}

.status {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  grid-row: 1;
}

.status.ok {
  background: var(--success);
}

.status.err {
  background: var(--danger);
}

.history-item .cmd {
  grid-column: 2;
  color: var(--text);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.meta {
  grid-column: 2;
  font-size: 11px;
  color: var(--text-3);
}

@media (max-width: 1100px) {
  .wrap {
    grid-template-columns: 1fr;
  }
}
</style>
