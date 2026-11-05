<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
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
        <p class="page-subtitle">直连实例执行原始命令；危险命令由平台策略拦截并全程审计。</p>
      </div>
      <div class="head-actions">
        <span class="session mono">
          <span class="dot"></span>{{ store.selected?.host }}:{{ store.selected?.port }}
        </span>
        <el-button size="small" @click="clear">清空会话</el-button>
      </div>
    </div>

    <div class="workspace">
      <section class="terminal">
        <header class="term-bar">
          <span class="term-title">会话 · {{ store.selected?.name ?? '未选择实例' }}</span>
          <span class="term-meta mono">{{ entries.length }} 条命令</span>
        </header>

        <div ref="outputRef" class="term-body mono">
          <div v-if="!entries.length" class="term-empty">
            <p class="term-empty-title">会话尚未开始</p>
            <p class="term-empty-desc">输入命令后回车执行；下方快捷命令可直接点击。</p>
          </div>

          <div v-for="(entry, index) in entries" :key="index" class="entry">
            <div class="cmd">
              <span class="prompt">{{ store.selected?.name }}&gt;</span>
              <span class="text">{{ entry.command }}</span>
              <span class="cost">{{ entry.costMs }} ms · {{ entry.at }}</span>
            </div>
            <pre :class="['reply', { error: entry.error }]">{{ render(entry.payload) }}</pre>
          </div>
        </div>

        <footer class="term-input">
          <span class="prompt">&gt;</span>
          <input
            v-model="command"
            class="command-input mono"
            placeholder="输入命令后回车执行"
            :disabled="busy"
            @keyup.enter="execute()"
          />
          <button class="send" type="button" :disabled="busy" @click="execute()">
            {{ busy ? '执行中' : '执行' }}
          </button>
        </footer>
      </section>

      <aside class="side">
        <section class="panel side-block">
          <div class="panel-title">
            快捷命令
            <span class="hint">点击执行</span>
          </div>
          <div class="chips">
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
        </section>

        <section class="panel side-block">
          <div class="panel-title">
            最近命令
            <span class="hint">点击填入输入框</span>
          </div>
          <div v-if="!history.length" class="side-empty">暂无命令记录</div>
          <ul v-else class="history">
            <li v-for="item in history" :key="item.id" class="history-item" @click="useHistory(item)">
              <span class="status" :class="item.success ? 'ok' : 'err'"></span>
              <span class="history-cmd mono">{{ item.command }}</span>
              <span class="history-meta">{{ item.costMs }} ms · {{ item.createdAt?.slice(11) }}</span>
            </li>
          </ul>
        </section>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.head-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: none;
}

.session {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  color: var(--text-2);
}

.session .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--success);
}

.workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 16px;
  align-items: start;
}

/* ---------- 终端工作区（浅色页面中的深色面） ---------- */

.terminal {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--term-line);
  border-radius: var(--radius);
  background: var(--term-bg);
  overflow: hidden;
  box-shadow: 0 1px 2px rgba(16, 24, 40, 0.06);
}

.term-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 38px;
  padding: 0 14px;
  border-bottom: 1px solid var(--term-line);
  background: var(--term-surface);
}

.term-title {
  font-size: 12px;
  font-weight: 500;
  color: var(--term-text);
}

.term-meta {
  font-size: 11px;
  color: var(--term-muted);
}

.term-body {
  height: 460px;
  overflow-y: auto;
  padding: 14px 16px;
  color: var(--term-text);
  font-size: 12.5px;
  line-height: 1.65;
}

.term-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: 4px;
  text-align: center;
}

.term-empty-title {
  margin: 0;
  font-size: 13px;
  color: #c6ccd4;
}

.term-empty-desc {
  margin: 0;
  font-size: 12px;
  color: var(--term-muted);
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
  color: var(--term-prompt);
  font-size: 12px;
}

.text {
  color: #e8ecf1;
  word-break: break-all;
}

.cost {
  margin-left: auto;
  font-size: 10.5px;
  color: var(--term-muted);
  white-space: nowrap;
}

.reply {
  margin: 6px 0 0;
  padding: 8px 10px;
  border: 1px solid var(--term-line);
  border-radius: 6px;
  background: var(--term-surface);
  color: #b9c1cb;
  font-size: 12.5px;
  white-space: pre-wrap;
  word-break: break-all;
}

.reply.error {
  border-color: #4a2a2c;
  background: #1e1416;
  color: var(--term-error);
}

/* ---------- 输入区 ---------- */

.term-input {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border-top: 1px solid var(--term-line);
  background: var(--term-surface);
}

.command-input {
  flex: 1;
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--term-line);
  border-radius: 6px;
  background: var(--term-bg);
  color: var(--term-text);
  font-size: 12.5px;
  outline: none;
  transition: border-color 0.14s var(--ease), box-shadow 0.14s var(--ease);
}

.command-input::placeholder {
  color: #5f6772;
}

.command-input:focus {
  border-color: var(--term-prompt);
  box-shadow: 0 0 0 3px rgba(122, 162, 247, 0.16);
}

.send {
  height: 32px;
  padding: 0 14px;
  border: 1px solid #2f3640;
  border-radius: 6px;
  background: #22262d;
  color: #e8ecf1;
  font-size: 12.5px;
  cursor: pointer;
  transition: background-color 0.14s var(--ease);
}

.send:hover:not(:disabled) {
  background: #2b3038;
}

.send:disabled {
  opacity: 0.6;
  cursor: default;
}

/* ---------- 侧栏 ---------- */

.side {
  display: grid;
  gap: 14px;
}

.side-block {
  padding: 14px 16px;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.chip {
  padding: 4px 8px;
  border: 1px solid var(--line);
  border-radius: 4px;
  background: var(--surface-2);
  color: var(--text-2);
  font-size: 11.5px;
  cursor: pointer;
  transition: background-color 0.14s var(--ease), color 0.14s var(--ease),
    border-color 0.14s var(--ease);
}

.chip:hover {
  background: var(--surface-3);
  border-color: var(--line-strong);
  color: var(--text-1);
}

.side-empty {
  padding: 18px 0;
  text-align: center;
  font-size: 12px;
  color: var(--text-3);
}

.history {
  list-style: none;
  margin: 0;
  padding: 0;
}

.history-item {
  display: grid;
  grid-template-columns: 6px minmax(0, 1fr);
  column-gap: 8px;
  padding: 7px 8px;
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
  margin-top: 6px;
}

.status.ok {
  background: var(--success);
}

.status.err {
  background: var(--danger);
}

.history-cmd {
  grid-column: 2;
  font-size: 12px;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-meta {
  grid-column: 2;
  font-size: 10.5px;
  color: var(--text-3);
}

@media (max-width: 1200px) {
  .workspace {
    grid-template-columns: 1fr;
  }
}
</style>
