<script setup lang="ts">
defineProps<{
  label: string
  value: string | number
  hint?: string
  /** 仅用于标记异常/告警等需要被注意的指标，默认保持中性 */
  tone?: 'default' | 'danger' | 'warn'
}>()
</script>

<template>
  <div class="stat" :class="tone && tone !== 'default' ? 'is-' + tone : undefined">
    <div class="label">{{ label }}</div>
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
  transition: border-color 0.14s var(--ease);
}

.stat:hover {
  border-color: var(--line-strong);
}

.label {
  font-size: 11.5px;
  font-weight: 500;
  letter-spacing: 0.03em;
  color: var(--text-3);
}

.value {
  margin-top: 8px;
  font-size: 22px;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--text);
  line-height: 1.15;
}

.hint {
  margin-top: 6px;
  font-size: 11.5px;
  color: var(--text-3);
}

.is-danger .value {
  color: #f47c78;
}

.is-warn .value {
  color: #e0b661;
}
</style>
