import { describe, expect, it } from 'vitest'
import { formatBytes, formatNumber, formatPercent } from './format'

describe('formatBytes', () => {
  it('小于 1KB 显示字节', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(512)).toBe('512 B')
  })

  it('按 1024 进制换算', () => {
    expect(formatBytes(1024)).toBe('1.00 KB')
    expect(formatBytes(1536)).toBe('1.50 KB')
    expect(formatBytes(1024 * 1024)).toBe('1.00 MB')
  })

  it('空值按 0 处理', () => {
    expect(formatBytes(undefined)).toBe('0 B')
    expect(formatBytes(null as unknown as number)).toBe('0 B')
  })
})

describe('formatNumber', () => {
  it('千分位分隔', () => {
    expect(formatNumber(1234567)).toBe('1,234,567')
  })

  it('支持小数位参数', () => {
    expect(formatNumber(12.345, 1)).toBe('12.3')
  })

  it('空值按 0 处理', () => {
    expect(formatNumber(undefined)).toBe('0')
    expect(formatNumber(null as unknown as number)).toBe('0')
  })
})

describe('formatPercent', () => {
  it('小数转百分比', () => {
    expect(formatPercent(0.8677)).toBe('86.8%')
    expect(formatPercent(1)).toBe('100.0%')
    expect(formatPercent(0)).toBe('0.0%')
  })

  it('超过 1 时原样计算（不做裁剪）', () => {
    expect(formatPercent(1.5)).toBe('150.0%')
  })
})