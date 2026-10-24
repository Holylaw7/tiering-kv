import { defineStore } from 'pinia'
import { instanceApi } from '@/api'
import type { InstanceView } from '@/api/types'

export const useInstanceStore = defineStore('instance', {
  state: () => ({
    list: [] as InstanceView[],
    selectedId: Number(localStorage.getItem('tk_selected_instance') || 0),
    loading: false
  }),
  getters: {
    selected(state): InstanceView | undefined {
      return state.list.find((item) => item.id === state.selectedId) ?? state.list[0]
    },
    upCount(state): number {
      return state.list.filter((item) => item.status === 'UP').length
    }
  },
  actions: {
    async load() {
      this.loading = true
      try {
        this.list = await instanceApi.list()
        if (!this.list.some((item) => item.id === this.selectedId)) {
          this.selectedId = this.list[0]?.id ?? 0
        }
        localStorage.setItem('tk_selected_instance', String(this.selectedId))
      } finally {
        this.loading = false
      }
    },
    select(id: number) {
      this.selectedId = id
      localStorage.setItem('tk_selected_instance', String(id))
    }
  }
})
