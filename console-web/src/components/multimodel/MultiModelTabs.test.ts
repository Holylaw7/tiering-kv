import { defineComponent, inject, provide, toRef } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import VectorTab from './VectorTab.vue'
import TimeSeriesTab from './TimeSeriesTab.vue'
import JsonTab from './JsonTab.vue'

const {
  addVectorMock,
  searchVectorMock,
  deleteVectorMock,
  dropVectorCollectionMock,
  checkpointVectorMock,
  queryTimeSeriesRangeMock,
  addTimeSeriesPointMock,
  getJsonMock,
  setJsonMock,
  deleteJsonMock,
  successSpy,
  warningSpy,
  confirmSpy
} = vi.hoisted(() => ({
  addVectorMock: vi.fn(),
  searchVectorMock: vi.fn(),
  deleteVectorMock: vi.fn(),
  dropVectorCollectionMock: vi.fn(),
  checkpointVectorMock: vi.fn(),
  queryTimeSeriesRangeMock: vi.fn(),
  addTimeSeriesPointMock: vi.fn(),
  getJsonMock: vi.fn(),
  setJsonMock: vi.fn(),
  deleteJsonMock: vi.fn(),
  successSpy: vi.fn(),
  warningSpy: vi.fn(),
  confirmSpy: vi.fn()
}))

vi.mock('@/api', () => ({
  multiModelApi: {
    addVector: (...args: unknown[]) => addVectorMock(...args),
    searchVector: (...args: unknown[]) => searchVectorMock(...args),
    deleteVector: (...args: unknown[]) => deleteVectorMock(...args),
    dropVectorCollection: (...args: unknown[]) => dropVectorCollectionMock(...args),
    checkpointVector: (...args: unknown[]) => checkpointVectorMock(...args),
    queryTimeSeriesRange: (...args: unknown[]) => queryTimeSeriesRangeMock(...args),
    addTimeSeriesPoint: (...args: unknown[]) => addTimeSeriesPointMock(...args),
    getJson: (...args: unknown[]) => getJsonMock(...args),
    setJson: (...args: unknown[]) => setJsonMock(...args),
    deleteJson: (...args: unknown[]) => deleteJsonMock(...args)
  }
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    success: successSpy,
    warning: warningSpy
  },
  ElMessageBox: {
    confirm: confirmSpy
  }
}))

const TABLE_ROWS_KEY = Symbol('mm-table-rows')

const ElTableStub = defineComponent({
  name: 'ElTable',
  props: { data: { type: Array, default: () => [] } },
  setup(props) {
    provide(TABLE_ROWS_KEY, toRef(props, 'data'))
    return {}
  },
  template: `
    <div class="el-table-stub">
      <div v-if="!data || data.length === 0" class="table-empty"><slot name="empty" /></div>
      <div v-else class="table-body"><slot /></div>
    </div>
  `
})

const ElTableColumnStub = defineComponent({
  name: 'ElTableColumn',
  props: { label: { type: String, default: '' }, prop: { type: String, default: '' } },
  setup() {
    const rows = inject<{ value: Record<string, unknown>[] }>(TABLE_ROWS_KEY, { value: [] })
    return { rows }
  },
  template: `
    <div class="el-col-stub" :data-label="label">
      <div v-for="(row, idx) in rows" :key="idx" class="el-cell-stub">
        <slot :row="row">{{ prop ? row[prop] : '' }}</slot>
      </div>
    </div>
  `
})

const ElButtonStub = defineComponent({
  name: 'ElButton',
  props: { disabled: Boolean, loading: Boolean },
  emits: ['click'],
  template: `<button class="el-btn-stub" :disabled="disabled" @click="$emit('click', $event)"><slot /></button>`
})

const ElInputStub = defineComponent({
  name: 'ElInput',
  props: { modelValue: { type: [String, Number], default: '' }, placeholder: { type: String, default: '' } },
  emits: ['update:modelValue'],
  template: `<input class="el-input-stub" :value="modelValue" :placeholder="placeholder" @input="$emit('update:modelValue', $event.target.value)" />`
})

const ElSelectStub = defineComponent({
  name: 'ElSelect',
  props: { modelValue: { type: [String, Number], default: '' } },
  emits: ['update:modelValue'],
  template: `<div class="el-select-stub"><slot /></div>`
})

const ElDialogStub = defineComponent({
  name: 'ElDialog',
  props: { modelValue: Boolean, title: { type: String, default: '' } },
  emits: ['update:modelValue'],
  template: `<div v-if="modelValue" class="el-dialog-stub" :data-title="title"><slot /><div class="dialog-footer"><slot name="footer" /></div></div>`
})

const ChartPanelStub = defineComponent({
  name: 'ChartPanel',
  props: ['title', 'hint', 'option', 'height'],
  template: `<div class="chart-panel-stub" :data-hint="hint">{{ title }}</div>`
})

const commonStubs = {
  'el-table': ElTableStub,
  'el-table-column': ElTableColumnStub,
  'el-button': ElButtonStub,
  'el-input': ElInputStub,
  'el-input-number': ElInputStub,
  'el-select': ElSelectStub,
  'el-option': { template: '<div />' },
  'el-dialog': ElDialogStub,
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { props: ['label'], template: '<div class="form-item" :data-label="label"><slot /></div>' },
  'el-progress': { props: ['percentage'], template: '<div class="progress-stub">{{ percentage }}</div>' },
  'el-icon': { template: '<span class="icon-stub"><slot /></span>' },
  ChartPanel: ChartPanelStub
}

describe('MultiModel tabs', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    confirmSpy.mockResolvedValue('confirm')
  })

  it('VectorTab supports collection auto-selection, search, add, delete, drop, and checkpoint', async () => {
    searchVectorMock.mockResolvedValue({
      collection: 'docs',
      costMs: 4,
      hits: [{ id: 'doc-1', score: 0.9123 }]
    })
    addVectorMock.mockResolvedValue(undefined)
    deleteVectorMock.mockResolvedValue(undefined)
    dropVectorCollectionMock.mockResolvedValue(undefined)
    checkpointVectorMock.mockResolvedValue(undefined)

    const wrapper = mount(VectorTab, {
      props: {
        instanceId: 7,
        vectorInfo: { supported: true, totalVectors: 2, collections: [{ name: 'docs', count: 2 }] },
        canOperate: true
      },
      global: { stubs: commonStubs }
    })

    expect(wrapper.text()).toContain('共 1 个集合 · 2 条向量')

    // Invalid query vector warns
    const queryInput = wrapper.find('input[placeholder*="输入查询向量"]')
    await queryInput.setValue('abc, def')
    const searchBtn = wrapper.findAll('button.el-btn-stub').find((b) => b.text().includes('检索'))!
    await searchBtn.trigger('click')
    expect(warningSpy).toHaveBeenCalled()

    // Valid search displays hits
    await queryInput.setValue('0.1, 0.2, 0.3, 0.4')
    await searchBtn.trigger('click')
    await flushPromises()
    expect(searchVectorMock).toHaveBeenCalledWith({
      instanceId: 7,
      collection: 'docs',
      vector: [0.1, 0.2, 0.3, 0.4],
      topK: 10
    })
    expect(wrapper.text()).toContain('doc-1')
    expect(wrapper.text()).toContain('0.9123')

    // Add vector dialog validation + submit
    await wrapper.findAll('button.el-btn-stub').find((b) => b.text().includes('写入向量'))!.trigger('click')
    expect(wrapper.find('.el-dialog-stub').exists()).toBe(true)
    const dialogSubmit = wrapper.find('.dialog-footer').findAll('button.el-btn-stub').find((b) => b.text().trim() === '写入')!
    await dialogSubmit.trigger('click')
    expect(warningSpy).toHaveBeenCalledWith('请填写向量 ID')

    await wrapper.find('input[placeholder="例如 doc-001"]').setValue('doc-2')
    await dialogSubmit.trigger('click')
    await flushPromises()
    expect(addVectorMock).toHaveBeenCalledWith({
      instanceId: 7,
      collection: 'docs',
      id: 'doc-2',
      values: [0.1, 0.2, 0.3, 0.4]
    })
    expect(wrapper.emitted('refresh')).toHaveLength(1)

    // Delete hit item & drop collection & checkpoint
    const deleteBtns = wrapper.findAll('button.el-btn-stub').filter((b) => b.text().trim() === '删除')
    await deleteBtns[1].trigger('click')
    await flushPromises()
    expect(deleteVectorMock).toHaveBeenCalledWith(7, 'docs', 'doc-1')

    await deleteBtns[0].trigger('click')
    await flushPromises()
    expect(dropVectorCollectionMock).toHaveBeenCalledWith(7, 'docs')

    await wrapper.findAll('button.el-btn-stub').find((b) => b.text().includes('索引落盘'))!.trigger('click')
    await flushPromises()
    expect(checkpointVectorMock).toHaveBeenCalledWith(7, 'docs')

    // Switching instanceId clears results
    await wrapper.setProps({ instanceId: undefined })
    await searchBtn.trigger('click')
    expect(warningSpy).toHaveBeenCalledWith('请先选择实例')
  })

  it('TimeSeriesTab auto-queries first series, supports aggregation query and point insertion', async () => {
    queryTimeSeriesRangeMock.mockResolvedValue({
      key: 'ts:cpu',
      totalLen: 2,
      aggregation: 'AVG',
      bucketMs: 60000,
      reducedValue: 42.5,
      points: [
        { timestamp: 1700000000000, value: 40 },
        { timestamp: 1700000060000, value: 45 }
      ]
    })
    addTimeSeriesPointMock.mockResolvedValue(undefined)

    const wrapper = mount(TimeSeriesTab, {
      props: {
        instanceId: 8,
        tsKeysInfo: {
          supported: true,
          series: [{ key: 'ts:cpu', length: 2, latestValue: 45 }]
        },
        canOperate: true
      },
      global: { stubs: commonStubs }
    })

    await flushPromises()
    expect(queryTimeSeriesRangeMock).toHaveBeenCalledWith(
      expect.objectContaining({ instanceId: 8, key: 'ts:cpu', from: '-inf', to: '+inf' })
    )
    expect(wrapper.text()).toContain('全序列 AVG = 42.5000')
    expect(wrapper.find('.chart-panel-stub').attributes('data-hint')).toContain('桶聚合 AVG')

    // Invalid timestamp warns
    const tsInput = wrapper.find('input[placeholder*="毫秒时间戳"]')
    await tsInput.setValue('12.34')
    const addBtn = wrapper.findAll('button.el-btn-stub').find((b) => b.text().includes('写入数据点'))!
    await addBtn.trigger('click')
    expect(warningSpy).toHaveBeenCalledWith('时间戳必须为整数毫秒，留空则使用当前时间')

    // Valid timestamp inserts point and emits refresh
    await tsInput.setValue('1700000120000')
    await addBtn.trigger('click')
    await flushPromises()
    expect(addTimeSeriesPointMock).toHaveBeenCalledWith({
      instanceId: 8,
      key: 'ts:cpu',
      timestamp: 1700000120000,
      value: 0
    })
    expect(wrapper.emitted('refresh')).toHaveLength(1)

    // Switch instance clears key -> query warns when empty
    await wrapper.setProps({ instanceId: 9 })
    await flushPromises()
    const queryBtn = wrapper.findAll('button.el-btn-stub').find((b) => b.text().includes('查询'))!
    await queryBtn.trigger('click')
    expect(warningSpy).toHaveBeenCalledWith('请输入或选择时序键名')
  })

  it('JsonTab auto-loads first key, drills into fields, edits, creates, and deletes paths', async () => {
    getJsonMock.mockResolvedValue({
      key: 'doc:1',
      path: '$',
      exists: true,
      valueType: 'object',
      prettyJson: '{"service":"tiering-kv"}',
      objectKeys: ['service']
    })
    setJsonMock.mockResolvedValue(undefined)
    deleteJsonMock.mockResolvedValue(undefined)

    const wrapper = mount(JsonTab, {
      props: {
        instanceId: 5,
        jsonKeysInfo: { supported: true, keys: ['doc:1'] },
        pattern: '*',
        canOperate: true
      },
      global: { stubs: commonStubs }
    })

    await flushPromises()
    expect(getJsonMock).toHaveBeenCalledWith(5, 'doc:1', '$')
    expect(wrapper.text()).toContain('object')

    // Pattern change & filter button
    const patternInput = wrapper.find('input[placeholder="键过滤模式 (*)"]')
    await patternInput.setValue('doc:*')
    expect(wrapper.emitted('update:pattern')?.[0]).toEqual(['doc:*'])
    await wrapper.findAll('button.el-btn-stub').find((b) => b.text().trim() === '筛选')!.trigger('click')
    expect(wrapper.emitted('refresh-keys')).toHaveLength(1)

    // Field drill-down
    await wrapper.findAll('button.el-btn-stub').find((b) => b.text().trim() === '.service')!.trigger('click')
    await flushPromises()
    expect(getJsonMock).toHaveBeenCalledWith(5, 'doc:1', '$.service')

    // Save edit
    await wrapper.findAll('button.el-btn-stub').find((b) => b.text().includes('保存修改'))!.trigger('click')
    await flushPromises()
    expect(setJsonMock).toHaveBeenCalledWith({
      instanceId: 5,
      key: 'doc:1',
      path: '$.service',
      value: '{"service":"tiering-kv"}'
    })

    // Delete path
    await wrapper.findAll('button.el-btn-stub').find((b) => b.text().includes('删除当前路径'))!.trigger('click')
    await flushPromises()
    expect(deleteJsonMock).toHaveBeenCalledWith(5, 'doc:1', '$.service')

    // Create new JSON doc dialog
    await wrapper.findAll('button.el-btn-stub').find((b) => b.text().includes('新建'))!.trigger('click')
    expect(wrapper.find('.el-dialog-stub').exists()).toBe(true)
    const saveCreateBtn = wrapper.find('.dialog-footer').findAll('button.el-btn-stub').find((b) => b.text().trim() === '保存')!
    await saveCreateBtn.trigger('click')
    expect(warningSpy).toHaveBeenCalledWith('请填写键名与 JSON 内容')

    await wrapper.find('input[placeholder="例如 doc:profile:1"]').setValue('doc:2')
    await saveCreateBtn.trigger('click')
    await flushPromises()
    expect(setJsonMock).toHaveBeenCalledWith(
      expect.objectContaining({ instanceId: 5, key: 'doc:2', path: '$' })
    )
  })
})
