<script setup lang="ts">
withDefaults(
  defineProps<{
    title: string
    description?: string
  }>(),
  { description: '' }
)
</script>

<template>
  <div class="empty">
    <svg class="mark" viewBox="0 0 48 48" fill="none" aria-hidden="true">
      <rect x="6.5" y="10.5" width="35" height="27" rx="3.5" stroke="currentColor" stroke-width="1.4" />
      <path d="M6.5 18.5h35" stroke="currentColor" stroke-width="1.4" />
      <circle cx="11.5" cy="14.5" r="1.1" fill="currentColor" />
      <circle cx="15.5" cy="14.5" r="1.1" fill="currentColor" />
      <path d="M13 25.5h10M13 30.5h6" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
    </svg>
    <p class="title">{{ title }}</p>
    <p v-if="description" class="desc">{{ description }}</p>
    <div class="actions"><slot /></div>
  </div>
</template>

<style scoped>
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 40px 16px 44px;
  text-align: center;
}

.mark {
  width: 44px;
  height: 44px;
  color: var(--line-strong);
  margin-bottom: 12px;
}

.title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
}

.desc {
  margin: 5px 0 0;
  max-width: 44ch;
  font-size: 12.5px;
  color: var(--text-2);
}

.actions:not(:empty) {
  margin-top: 16px;
  display: flex;
  gap: 8px;
}
</style>
