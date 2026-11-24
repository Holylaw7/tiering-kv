import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { InstanceView } from '@/api/types'

const listMock = vi.fn()

vi.mock('@/api', () => ({
  instanceApi: {
    list: (...args: unknown[]) => listMock(...args)
  }
}))

import { useInstanceStore } from './instance'

function createMemoryStorage() {
  const data = new Map<string, string>()
  return {
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => {
      data.set(key, String(value))
    },
    removeItem: (key: string) => {
      data.delete(key)
    }
  }
}

const sampleInstances: InstanceView[] = [
  { id: 1, name: 'local-6379', host: '127.0.0.1', port: 6379, status: 'UP', enabled: true, hasPassword: false },
  { id: 2, name: 'cold-6380', host: '127.0.0.1', port: 6380, status: 'DOWN', enabled: true, hasPassword: true }
]

describe('useInstanceStore', () => {
  let storage: ReturnType<typeof createMemoryStorage>

  beforeEach(() => {
    storage = createMemoryStorage()
    vi.stubGlobal('localStorage', storage)
    setActivePinia(createPinia())
    listMock.mockReset()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('loads instances, falls back to first item when persisted id is missing, and counts UP instances', async () => {
    storage.setItem('tk_selected_instance', '999')
    setActivePinia(createPinia())
    listMock.mockResolvedValue([...sampleInstances])

    const store = useInstanceStore()
    await store.load()

    expect(store.loading).toBe(false)
    expect(store.list).toHaveLength(2)
    expect(store.selectedId).toBe(1)
    expect(store.selected?.name).toBe('local-6379')
    expect(store.upCount).toBe(1)
    expect(storage.getItem('tk_selected_instance')).toBe('1')
  })

  it('preserves valid persisted selection, updates selection, and merges live instance state', async () => {
    storage.setItem('tk_selected_instance', '2')
    setActivePinia(createPinia())
    listMock.mockResolvedValue(sampleInstances.map((item) => ({ ...item })))

    const store = useInstanceStore()
    await store.load()
    expect(store.selectedId).toBe(2)
    expect(store.selected?.name).toBe('cold-6380')

    store.syncInstance({ ...sampleInstances[1], status: 'UP', version: '5.1.0' })
    expect(store.upCount).toBe(2)
    expect(store.selected?.version).toBe('5.1.0')

    store.select(1)
    expect(store.selectedId).toBe(1)
    expect(storage.getItem('tk_selected_instance')).toBe('1')
  })
})
