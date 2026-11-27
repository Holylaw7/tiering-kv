import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import SstableInventoryDrawer from './SstableInventoryDrawer.vue'

const { tablesMock, successSpy, errorSpy } = vi.hoisted(() => ({
  tablesMock: vi.fn(),
  successSpy: vi.fn(),
  errorSpy: vi.fn()
}))

vi.mock('@/api', () => ({
  systemApi: {
    tables: (...args: unknown[]) => tablesMock(...args)
  }
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    success: successSpy,
    error: errorSpy
  }
}))

const ElDrawerStub = {
  template: '<div class="drawer-stub"><slot /></div>',
  props: ['modelValue', 'title', 'size'],
  emits: ['update:modelValue']
}

const ElTableStub = {
  name: 'ElTable',
  props: ['data', 'emptyText'],
  emits: ['sort-change'],
  template: `
    <div class="table-stub">
      <div v-for="row in data" :key="row.id" class="row-stub" :data-id="row.id">
        <slot />
      </div>
    </div>
  `
}

describe('SstableInventoryDrawer', () => {
  beforeEach(() => {
    tablesMock.mockReset()
    successSpy.mockClear()
    errorSpy.mockClear()
  })

  it('loads SSTable inventory when opened and renders bytewise badge and summary', async () => {
    tablesMock.mockResolvedValueOnce({
      count: 2,
      totalBytes: 4096,
      totalEntries: 150,
      keyOrder: 'bytewise',
      tables: [
        { id: 2, keys: 50, bytes: 1024, firstKey: 'a', lastKey: 'm', firstKeyBase64: 'YQ==', lastKeyBase64: 'bQ==' },
        { id: 1, keys: 100, bytes: 3072, firstKey: 'n', lastKey: 'z', firstKeyBase64: 'bg==', lastKeyBase64: 'eg==' }
      ]
    })

    const wrapper = mount(SstableInventoryDrawer, {
      props: { modelValue: false, instanceId: 7 },
      global: {
        directives: { loading: () => {} },
        stubs: {
          'el-drawer': ElDrawerStub,
          'el-table': ElTableStub,
          'el-table-column': { template: '<div />' },
          'el-tooltip': { template: '<div><slot /></div>' },
          'el-pagination': { template: '<div class="pager-stub" />' }
        }
      }
    })

    expect(tablesMock).not.toHaveBeenCalled()
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    expect(tablesMock).toHaveBeenCalledWith(7)
    expect(wrapper.find('.order-badge').text()).toBe('BYTEWISE ORDER')
    expect(wrapper.find('.tables-summary').text()).toContain('2 张表')
    expect(wrapper.find('.tables-summary').text()).toContain('150 entries')

    // Trigger sort-change callback on el-table
    wrapper.findComponent(ElTableStub).vm.$emit('sort-change', { prop: 'bytes', order: 'descending' })
    await flushPromises()
    const rows = wrapper.findAll('.row-stub').map((el) => el.attributes('data-id'))
    expect(rows).toEqual(['1', '2'])
  })

  it('displays error banner when systemApi.tables fails and handles unknown keyOrder', async () => {
    tablesMock.mockRejectedValueOnce(new Error('实例未连接'))

    const wrapper = mount(SstableInventoryDrawer, {
      props: { modelValue: false, instanceId: 9 },
      global: {
        directives: { loading: () => {} },
        stubs: {
          'el-drawer': ElDrawerStub,
          'el-table': ElTableStub,
          'el-table-column': { template: '<div />' },
          'el-tooltip': { template: '<div><slot /></div>' },
          'el-pagination': { template: '<div />' }
        }
      }
    })

    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    expect(wrapper.find('.order-badge').text()).toBe('排序语义未知')
    expect(wrapper.find('.tables-error').text()).toBe('实例未连接')
  })

  it('copies raw Base64 range to clipboard and handles clipboard rejection', async () => {
    tablesMock.mockResolvedValueOnce({
      count: 1,
      totalBytes: 512,
      totalEntries: 10,
      keyOrder: 'bytewise',
      tables: [
        { id: 1, keys: 10, bytes: 512, firstKey: 'k1', lastKey: 'k9', firstKeyBase64: 'azE=', lastKeyBase64: 'azk=' }
      ]
    })

    const writeText = vi.fn().mockResolvedValueOnce(undefined).mockRejectedValueOnce(new Error('denied'))
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true
    })

    const wrapper = mount(SstableInventoryDrawer, {
      props: { modelValue: false, instanceId: 3 },
      global: {
        directives: { loading: () => {} },
        stubs: {
          'el-drawer': ElDrawerStub,
          'el-table': {
            props: ['data'],
            template: '<div><slot /></div>'
          },
          'el-table-column': {
            props: ['label'],
            template: '<div><slot v-if="label === \'操作\'" :row="{ id: 1, keys: 10, bytes: 512, firstKey: \'k1\', lastKey: \'k9\', firstKeyBase64: \'azE=\', lastKeyBase64: \'azk=\' }" /></div>'
          },
          'el-tooltip': { template: '<div><slot /></div>' },
          'el-pagination': { template: '<div />' }
        }
      }
    })

    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    const copyBtn = wrapper.find('.link-btn')
    await copyBtn.trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledWith('first_b64=azE=\nlast_b64=azk=')
    expect(successSpy).toHaveBeenCalledTimes(1)

    await copyBtn.trigger('click')
    await flushPromises()
    expect(errorSpy).toHaveBeenCalledTimes(1)
  })
})
