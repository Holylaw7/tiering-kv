import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import StatCard from './StatCard.vue'
import StatusBadge from './StatusBadge.vue'
import EmptyState from './EmptyState.vue'
import PageToolbar from './PageToolbar.vue'

describe('Common UI components', () => {
  it('renders StatCard with tone classes, hint, and badge slot', () => {
    const wrapper = mount(StatCard, {
      props: { label: '触发中告警', value: 3, hint: 'FIRING', tone: 'danger' },
      slots: { badge: '<span class="custom-badge">CRIT</span>' }
    })
    expect(wrapper.find('.stat').classes()).toContain('is-danger')
    expect(wrapper.find('.value').text()).toBe('3')
    expect(wrapper.find('.hint').text()).toBe('FIRING')
    expect(wrapper.find('.custom-badge').text()).toBe('CRIT')

    const defWrapper = mount(StatCard, {
      props: { label: '总数', value: 0, tone: 'default' }
    })
    expect(defWrapper.find('.stat').classes()).not.toContain('is-default')
    expect(defWrapper.find('.hint').exists()).toBe(false)
  })

  it('renders StatusBadge with default neutral tone, custom tone, dot, and mono flags', () => {
    const defBadge = mount(StatusBadge, { props: { label: 'IDLE' } })
    expect(defBadge.classes()).toContain('tone-neutral')
    expect(defBadge.find('.badge-dot').exists()).toBe(false)

    const liveBadge = mount(StatusBadge, {
      props: { label: 'SUCCESS', tone: 'success', dot: true, mono: true }
    })
    expect(liveBadge.classes()).toContain('tone-success')
    expect(liveBadge.classes()).toContain('is-mono')
    expect(liveBadge.find('.badge-dot').exists()).toBe(true)
  })

  it('renders EmptyState with optional description and action slot', () => {
    const minimal = mount(EmptyState, { props: { title: '暂无数据' } })
    expect(minimal.find('.title').text()).toBe('暂无数据')
    expect(minimal.find('.desc').exists()).toBe(false)

    const rich = mount(EmptyState, {
      props: { title: '暂无备份', description: '点击右上角立即备份' },
      slots: { default: '<button class="act">创建</button>' }
    })
    expect(rich.find('.desc').text()).toBe('点击右上角立即备份')
    expect(rich.find('.act').text()).toBe('创建')
  })

  it('renders PageToolbar slots and optional meta text', () => {
    const wrapper = mount(PageToolbar, {
      props: { meta: '共 12 条' },
      slots: {
        default: '<span class="left">filter</span>',
        right: '<button class="right">refresh</button>'
      }
    })
    expect(wrapper.find('.left').text()).toBe('filter')
    expect(wrapper.find('.toolbar-meta').text()).toBe('共 12 条')
    expect(wrapper.find('.right').text()).toBe('refresh')
  })
})
