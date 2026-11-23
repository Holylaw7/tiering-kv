<script setup lang="ts">
defineProps<{
  label: string
  value: string | number
  hint?: string
  tone?: 'default' | 'accent' | 'success' | 'warn' | 'danger'
}>()
</script>

<template>
  <div class="stat" :class="tone && tone !== 'default' ? 'is-' + tone : undefined">
    <div class="stat-top">
      <span class="label">{{ label }}</span>
      <slot name="badge" />
    </div>
    <div class="value num">{{ value }}</div>
    <div v-if="hint" class="hint">{{ hint }}</div>
  </div>
</template>

<style scoped>
.stat {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 14px 16px 13px;
  box-shadow: var(--shadow-card);
  transition: border-color 0.14s var(--ease);
}

.stat:hover {
  border-color: var(--line-strong);
}

.stat-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.label {
  font-size: var(--text-xs);
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--text-3);
  text-transform: uppercase;
}

.value {
  margin-top: 8px;
  font-size: 22px;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--text);
  line-height: 1.2;
}

.hint {
  margin-top: 5px;
  font-size: var(--text-xs);
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.is-accent .value {
  color: var(--accent);
}

.is-success .value {
  color: var(--success);
}

.is-warn {
  border-color: var(--warn-line);
}

.is-warn .value {
  color: var(--warn);
}

.is-danger {
  border-color: var(--danger-line);
}

.is-danger .value {
  color: var(--danger);
}
</style>
