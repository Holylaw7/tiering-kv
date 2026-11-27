import { defineComponent, inject, provide, toRef } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import type { BackupRecord, BackupScheduleView, InstanceView, RestorePreviewView } from '@/api/types'
import BackupSchedulePanel from './BackupSchedulePanel.vue'
import BackupRecordTable from './BackupRecordTable.vue'
import RestorePreviewDialog from './RestorePreviewDialog.vue'

const { warningSpy } = vi.hoisted(() => ({
  warningSpy: vi.fn()
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    warning: warningSpy
  }
}))

const TABLE_ROWS_KEY = Symbol('table-rows')

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

const ElCheckboxStub = defineComponent({
  name: 'ElCheckbox',
  props: { modelValue: Boolean },
  emits: ['update:modelValue'],
  template: `<label class="el-checkbox-stub"><input type="checkbox" :checked="modelValue" @change="$emit('update:modelValue', $event.target.checked)" /><slot /></label>`
})

const commonStubs = {
  'el-table': ElTableStub,
  'el-table-column': ElTableColumnStub,
  'el-button': ElButtonStub,
  'el-input': ElInputStub,
  'el-input-number': ElInputStub,
  'el-select': ElSelectStub,
  'el-option': { template: '<div class="el-option-stub"><slot /></div>' },
  'el-dialog': ElDialogStub,
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { props: ['label'], template: '<div class="form-item" :data-label="label"><slot /></div>' },
  'el-radio-group': { props: ['modelValue'], emits: ['update:modelValue'], template: '<div><slot /></div>' },
  'el-radio': { template: '<label><slot /></label>' },
  'el-switch': { props: ['modelValue'], emits: ['update:modelValue'], template: '<input type="checkbox" />' },
  'el-tooltip': { props: ['content'], template: '<div class="tooltip-stub" :data-tip="content"><slot /></div>' },
  'el-progress': { props: ['percentage'], template: '<div class="progress-stub">{{ percentage }}%</div>' },
  'el-alert': { props: ['type', 'title'], template: '<div class="alert-stub" :data-type="type">{{ title }}</div>' },
  'el-checkbox': ElCheckboxStub,
  'el-icon': { template: '<span class="icon-stub"><slot /></span>' }
}

const sampleInstances: InstanceView[] = [
  { id: 1, name: 'primary', host: '127.0.0.1', port: 6379, status: 'UP', enabled: true, hasPassword: false }
]

const sampleSchedules: BackupScheduleView[] = [
  {
    id: 11,
    instanceId: 1,
    instanceName: 'primary',
    name: 'daily-logical',
    cronExpression: '0 2 * * *',
    mode: 'LOGICAL',
    retentionCount: 7,
    enabled: true,
    lastStatus: 'SUCCESS',
    lastMessage: 'ok',
    nextRunAt: '2026-11-28 02:00:00'
  },
  {
    id: 12,
    instanceId: 1,
    instanceName: 'primary',
    name: 'weekly-bgsave',
    cronExpression: '0 3 * * SUN',
    mode: 'CHECKPOINT',
    retentionCount: 3,
    enabled: false,
    lastStatus: 'FAILED',
    lastMessage: 'disk full'
  },
  {
    id: 13,
    instanceId: 1,
    instanceName: 'primary',
    name: 'pending-job',
    cronExpression: '0 * * * *',
    mode: 'LOGICAL',
    retentionCount: 5,
    enabled: true,
    lastMessage: 'waiting'
  }
]

describe('BackupSchedulePanel', () => {
  beforeEach(() => {
    warningSpy.mockClear()
  })

  it('renders schedule list, emits row actions, validates inputs, and saves create/edit payloads', async () => {
    const wrapper = mount(BackupSchedulePanel, {
      props: {
        schedules: sampleSchedules,
        instances: sampleInstances,
        selectedInstanceId: 1,
        canOperate: true,
        saving: false,
        runningScheduleId: null,
        dialogVisible: false
      },
      global: { stubs: commonStubs }
    })

    expect(wrapper.text()).toContain('已启用 2 / 3 条')
    expect(wrapper.text()).toContain('物理 BGSAVE')
    expect(wrapper.text()).toContain('逻辑导出')
    expect(wrapper.text()).toContain('FAILED')

    // Trigger row actions on first schedule
    const buttons = wrapper.findAll('button.el-btn-stub')
    const runBtn = buttons.find((b) => b.text().includes('立即执行'))!
    await runBtn.trigger('click')
    expect(wrapper.emitted('run-now')?.[0]?.[0]).toEqual(sampleSchedules[0])

    const toggleBtn = buttons.find((b) => b.text().trim() === '停用')!
    await toggleBtn.trigger('click')
    expect(wrapper.emitted('toggle')?.[0]?.[0]).toEqual(sampleSchedules[0])

    const removeBtn = buttons.find((b) => b.text().trim() === '删除')!
    await removeBtn.trigger('click')
    expect(wrapper.emitted('remove')?.[0]?.[0]).toEqual(sampleSchedules[0])

    // Open create modal
    const createBtn = buttons.find((b) => b.text().includes('新建定时策略'))!
    await createBtn.trigger('click')
    expect(wrapper.emitted('update:dialogVisible')?.[0]).toEqual([true])
    await wrapper.setProps({ dialogVisible: true })
    expect(wrapper.find('.el-dialog-stub').attributes('data-title')).toBe('新建定时备份策略')

    // Validation: empty name
    const saveBtn = wrapper.findAll('button.el-btn-stub').find((b) => b.text().trim() === '保存')!
    await saveBtn.trigger('click')
    expect(warningSpy).toHaveBeenCalledWith('请填写策略名称')

    // Fill name, clear cron -> cron warning
    const inputs = wrapper.findAll('input.el-input-stub')
    await inputs[0].setValue('  nightly-backup  ')
    await inputs[1].setValue('   ')
    await saveBtn.trigger('click')
    expect(warningSpy).toHaveBeenCalledWith('请填写 Cron 表达式')

    // Click cron preset and submit
    const presetBtn = wrapper.findAll('button.el-btn-stub').find((b) => b.text().includes('每 6 小时'))!
    await presetBtn.trigger('click')
    await saveBtn.trigger('click')
    expect(wrapper.emitted('save')?.[0]).toEqual([
      null,
      {
        instanceId: 1,
        name: 'nightly-backup',
        cronExpression: '0 */6 * * *',
        mode: 'LOGICAL',
        retentionCount: 7,
        enabled: true
      }
    ])

    // Open edit modal for existing row
    const editBtn = wrapper.findAll('button.el-btn-stub').find((b) => b.text().trim() === '编辑')!
    await editBtn.trigger('click')
    expect(wrapper.find('.el-dialog-stub').attributes('data-title')).toBe('编辑定时备份策略')
    await saveBtn.trigger('click')
    expect(wrapper.emitted('save')?.[1]?.[0]).toBe(11)
  })

  it('warns when saving without a selected instance and renders empty state', async () => {
    const wrapper = mount(BackupSchedulePanel, {
      props: {
        schedules: [],
        instances: [],
        canOperate: true,
        saving: false,
        runningScheduleId: null,
        dialogVisible: true
      },
      global: { stubs: commonStubs }
    })

    expect(wrapper.text()).toContain('暂无定时备份策略')
    const saveBtn = wrapper.findAll('button.el-btn-stub').find((b) => b.text().trim() === '保存')!
    await saveBtn.trigger('click')
    expect(warningSpy).toHaveBeenCalledWith('请选择目标实例')

    const cancelBtn = wrapper.findAll('button.el-btn-stub').find((b) => b.text().trim() === '取消')!
    await cancelBtn.trigger('click')
    expect(wrapper.emitted('update:dialogVisible')).toContainEqual([false])
  })
})

describe('BackupRecordTable', () => {
  const rows: BackupRecord[] = [
    {
      id: 1,
      instanceId: 1,
      fileName: 'snap-1.jsonl.gz',
      sizeBytes: 2048,
      keyCount: 50,
      status: 'SUCCESS',
      verifyStatus: 'VERIFIED',
      sha256: '0123456789abcdef0123456789abcdef',
      verifiedAt: '2026-11-27 10:01:00',
      restoreStatus: 'SUCCEEDED',
      restoredKeys: 50,
      preRestoreBackupId: 99,
      createdAt: '2026-11-27 10:00:00'
    },
    {
      id: 2,
      instanceId: 1,
      fileName: 'snap-2.jsonl',
      sizeBytes: 512,
      keyCount: 100,
      processedKeys: 42,
      status: 'RUNNING',
      restoreStatus: 'RESTORING',
      restoredKeys: 30,
      createdAt: '2026-11-27 10:05:00'
    },
    {
      id: 3,
      instanceId: 1,
      fileName: 'snap-3.jsonl.gz',
      sizeBytes: 0,
      keyCount: 10,
      status: 'FAILED',
      restoreStatus: 'FAILED',
      restoreMessage: 'connection reset',
      restoredKeys: 2,
      createdAt: '2026-11-27 10:08:00'
    }
  ]

  it('renders statuses, integrity badges, restore states and emits row actions', async () => {
    const wrapper = mount(BackupRecordTable, {
      props: {
        rows,
        filteredRows: rows,
        statusFilter: '',
        canOperate: true,
        isAdmin: true,
        verifyingId: null
      },
      global: { stubs: commonStubs }
    })

    expect(wrapper.text()).toContain('展示 3 / 3 条')
    expect(wrapper.text()).toContain('GZIP')
    expect(wrapper.text()).toContain('JSONL')
    expect(wrapper.text()).toContain('已导出 42 键')
    expect(wrapper.text()).toContain('保护快照 #99')
    expect(wrapper.text()).toContain('30%')
    expect(wrapper.text()).toContain('失败 (2/10)')

    wrapper.findComponent(ElSelectStub).vm.$emit('update:modelValue', 'SUCCESS')
    expect(wrapper.emitted('update:statusFilter')?.[0]).toEqual(['SUCCESS'])

    const btns = wrapper.findAll('button.el-btn-stub')
    await btns.find((b) => b.text().includes('刷新列表'))!.trigger('click')
    expect(wrapper.emitted('refresh')).toHaveLength(1)

    await btns.find((b) => b.text().trim() === '校验')!.trigger('click')
    expect(wrapper.emitted('verify')?.[0]?.[0]).toEqual(rows[0])

    await btns.find((b) => b.text().trim() === '下载')!.trigger('click')
    expect(wrapper.emitted('download')?.[0]?.[0]).toEqual(rows[0])

    await btns.find((b) => b.text().trim() === '恢复')!.trigger('click')
    expect(wrapper.emitted('restore')?.[0]?.[0]).toEqual(rows[0])

    await btns.find((b) => b.text().trim() === '删除')!.trigger('click')
    expect(wrapper.emitted('remove')?.[0]?.[0]).toEqual(rows[0])
  })
})

describe('RestorePreviewDialog', () => {
  const target: BackupRecord = {
    id: 5,
    instanceId: 1,
    fileName: 'snap-5.jsonl.gz',
    sizeBytes: 4096,
    keyCount: 25,
    status: 'SUCCESS',
    verifyStatus: 'VERIFIED',
    createdAt: '2026-11-27 11:00:00'
  }

  const preview: RestorePreviewView = {
    backupId: 5,
    fileName: 'snap-5.jsonl.gz',
    totalKeys: 25,
    conflictKeys: 4,
    newKeys: 20,
    expiredKeys: 1,
    archiveReadable: true,
    verifyStatus: 'VERIFIED',
    warning: '存在 4 个冲突键将被覆盖',
    typeBreakdown: { string: 20, hash: 5 },
    sampleConflicts: ['user:1', 'user:2']
  }

  it('displays conflict summary, type breakdown, sample conflicts, and emits confirm/toggle', async () => {
    const wrapper = mount(RestorePreviewDialog, {
      props: {
        modelValue: true,
        target,
        preview,
        previewLoading: false,
        submitting: false,
        createSafetySnapshot: true
      },
      global: {
        directives: { loading: () => {} },
        stubs: commonStubs
      }
    })

    expect(wrapper.find('.el-dialog-stub').attributes('data-title')).toBe('安全恢复向导（Dry-Run 冲突预览）')
    expect(wrapper.find('.alert-stub').attributes('data-type')).toBe('warning')
    expect(wrapper.text()).toContain('snap-5.jsonl.gz')
    expect(wrapper.text()).toContain('string: 20')
    expect(wrapper.text()).toContain('user:1')

    await wrapper.find('input[type="checkbox"]').setValue(false)
    expect(wrapper.emitted('update:createSafetySnapshot')?.[0]).toEqual([false])

    const confirmBtn = wrapper.findAll('button.el-btn-stub').find((b) => b.text().includes('确认执行恢复'))!
    expect(confirmBtn.attributes('disabled')).toBeUndefined()
    await confirmBtn.trigger('click')
    expect(wrapper.emitted('confirm')).toHaveLength(1)

    const cancelBtn = wrapper.findAll('button.el-btn-stub').find((b) => b.text().trim() === '取消')!
    await cancelBtn.trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([false])
  })

  it('shows error alert and disables confirm when archive is corrupted or unreadable', () => {
    const wrapper = mount(RestorePreviewDialog, {
      props: {
        modelValue: true,
        target,
        preview: { ...preview, archiveReadable: false, verifyStatus: 'CORRUPTED', warning: 'SHA-256 校验失败' },
        previewLoading: false,
        submitting: false,
        createSafetySnapshot: true
      },
      global: {
        directives: { loading: () => {} },
        stubs: commonStubs
      }
    })

    expect(wrapper.find('.alert-stub').attributes('data-type')).toBe('error')
    const confirmBtn = wrapper.findAll('button.el-btn-stub').find((b) => b.text().includes('确认执行恢复'))!
    expect(confirmBtn.attributes('disabled')).toBeDefined()
  })
})
