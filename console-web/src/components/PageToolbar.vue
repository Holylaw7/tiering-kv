<script setup lang="ts">
defineProps<{
  meta?: string
}>()
</script>

<template>
  <div class="toolbar">
    <div class="toolbar-group">
      <slot />
    </div>
    <div class="toolbar-group">
      <span v-if="meta" class="toolbar-meta">{{ meta }}</span>
      <slot name="right" />
    </div>
  </div>
</template>
