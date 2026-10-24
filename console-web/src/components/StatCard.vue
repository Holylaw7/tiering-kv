<script setup lang="ts">
defineProps<{
  label: string
  value: string | number
  hint?: string
  accent?: string
  icon?: string
}>()
</script>

<template>
  <div class="stat-card">
    <div class="stat-head">
      <span class="stat-label">{{ label }}</span>
      <el-icon v-if="icon" :style="{ color: accent || 'var(--el-color-primary)' }" :size="18">
        <component :is="icon" />
      </el-icon>
    </div>
    <div class="stat-value" :style="{ color: accent || 'inherit' }">{{ value }}</div>
    <div v-if="hint" class="stat-hint">{{ hint }}</div>
  </div>
</template>

<style scoped>
.stat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
