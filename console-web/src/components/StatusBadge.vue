<script setup lang="ts">
import { computed } from 'vue'

export type BadgeTone = 'neutral' | 'accent' | 'success' | 'warn' | 'danger' | 'cache'

const props = withDefaults(
  defineProps<{
    label: string
    tone?: BadgeTone
    dot?: boolean
    mono?: boolean
  }>(),
  {
    tone: 'neutral',
    dot: false,
    mono: false
  }
)

const toneClass = computed(() => `tone-${props.tone}`)
</script>

<template>
  <span class="status-badge" :class="[toneClass, { 'is-mono': mono }]">
    <span v-if="dot" class="badge-dot" aria-hidden="true"></span>
    <span>{{ label }}</span>
  </span>
</template>

<style scoped>
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 22px;
  padding: 0 8px;
  border-radius: 5px;
  border: 1px solid var(--line);
  background: var(--surface-3);
  color: var(--text-2);
  font-size: var(--text-xs);
  font-weight: 500;
  line-height: 1;
  white-space: nowrap;
}

.status-badge.is-mono {
  font-family: var(--font-mono);
}

.badge-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  flex: none;
}

.tone-accent {
  background: var(--accent-soft);
  border-color: var(--accent-line);
  color: var(--accent);
}

.tone-success {
  background: var(--success-soft);
  border-color: var(--success-line);
  color: var(--success);
}

.tone-warn {
  background: var(--warn-soft);
  border-color: var(--warn-line);
  color: var(--warn);
}

.tone-danger {
  background: var(--danger-soft);
  border-color: var(--danger-line);
  color: var(--danger);
}

.tone-cache {
  background: var(--tier-cache-soft);
  border-color: #bfe7e2;
  color: var(--tier-cache);
}
</style>
