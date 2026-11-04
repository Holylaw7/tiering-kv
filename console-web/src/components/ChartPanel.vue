<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsOption } from 'echarts'

echarts.use([LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const props = defineProps<{
  title?: string
  hint?: string
  option: EChartsOption
  height?: string
}>()

const container = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

function render() {
  if (!chart) return
  chart.setOption(props.option, true)
}

function resize() {
  chart?.resize()
}

onMounted(() => {
  if (container.value) {
    chart = echarts.init(container.value, undefined, { renderer: 'canvas' })
    render()
    window.addEventListener('resize', resize)
  }
})

watch(() => props.option, render, { deep: true })

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <section class="panel chart">
    <header class="head">
      <h3 class="title">{{ title }}</h3>
      <span v-if="hint" class="hint">{{ hint }}</span>
    </header>
    <div ref="container" class="canvas" :style="{ height: height || '240px' }"></div>
  </section>
</template>

<style scoped>
.chart {
  padding: 14px 16px 10px;
}

.head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 6px;
}

.title {
  margin: 0;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text);
}

.hint {
  font-size: 11.5px;
  color: var(--text-3);
}

.canvas {
  width: 100%;
}
</style>
