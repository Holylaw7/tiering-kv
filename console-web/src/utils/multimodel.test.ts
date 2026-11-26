import { describe, expect, it } from 'vitest'
import {
  formatTsAxisLabel,
  normalizeSimilarityPercent,
  parseVectorText
} from './multimodel'

describe('multimodel utils', () => {
  it('parses bracketed, comma-separated and whitespace-separated vectors', () => {
    expect(parseVectorText('[0.1, 0.2, -0.3]')).toEqual([0.1, 0.2, -0.3])
    expect(parseVectorText('1 2.5  3')).toEqual([1, 2.5, 3])
    expect(() => parseVectorText('   ')).toThrow('至少 1 个')
    expect(() => parseVectorText('1, abc, 3')).toThrow('第 2 维')
  })

  it('normalizes similarity and distance scores into [0, 100]', () => {
    expect(normalizeSimilarityPercent(0.95)).toBe(95)
    expect(normalizeSimilarityPercent(1)).toBe(100)
    expect(normalizeSimilarityPercent(0)).toBe(0)
    expect(normalizeSimilarityPercent(-0.5)).toBe(25)
    expect(normalizeSimilarityPercent(3)).toBe(25)
    expect(normalizeSimilarityPercent(Number.NaN)).toBe(0)
  })

  it('formats epoch millis as HH:mm:ss and preserves logical integer timestamps', () => {
    expect(formatTsAxisLabel(1000)).toBe('1000')
    expect(formatTsAxisLabel(1732600000000)).toMatch(/^\d{2}:\d{2}:\d{2}$/)
  })
})
