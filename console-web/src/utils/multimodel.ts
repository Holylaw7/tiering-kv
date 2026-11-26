/** 多模型工作台纯函数工具（v6.2.0 C1）：向量解析、相似度映射与时间戳格式化。 */

/** 解析逗号/空白/方括号分隔的浮点向量，返回有限实数数组；非法输入抛出可读错误。 */
export function parseVectorText(raw: string): number[] {
  const cleaned = raw.trim().replace(/^\[/, '').replace(/\]$/, '').trim()
  if (!cleaned) {
    throw new Error('请输入至少 1 个浮点数值，例如：0.1, 0.2, 0.3')
  }
  const tokens = cleaned.split(/[\s,]+/).filter(Boolean)
  const values: number[] = []
  for (let i = 0; i < tokens.length; i++) {
    const num = Number(tokens[i])
    if (!Number.isFinite(num)) {
      throw new Error(`第 ${i + 1} 维「${tokens[i]}」不是有效数字`)
    }
    values.push(num)
  }
  if (values.length > 2048) {
    throw new Error('单条向量维度不能超过 2048')
  }
  return values
}

/** 将相似度/距离分值映射为 [0, 100] 可视化进度条百分比。 */
export function normalizeSimilarityPercent(score: number): number {
  if (!Number.isFinite(score)) return 0
  if (score >= 0 && score <= 1) {
    return Math.round(score * 100)
  }
  if (score >= -1 && score < 0) {
    return Math.round(((score + 1) / 2) * 100)
  }
  // 欧氏距离等大于 1 的分值做单调有界压缩
  return Math.max(0, Math.min(100, Math.round((1 / (1 + Math.abs(score))) * 100)))
}

/** 将毫秒或自定义整型时间戳格式化为简洁图表坐标轴文本。 */
export function formatTsAxisLabel(ts: number): string {
  // 1e11 以上视为 Unix 毫秒时间戳，否则保留原始逻辑时间刻度
  if (ts >= 100_000_000_000) {
    const d = new Date(ts)
    if (!Number.isNaN(d.getTime())) {
      const hh = String(d.getHours()).padStart(2, '0')
      const mm = String(d.getMinutes()).padStart(2, '0')
      const ss = String(d.getSeconds()).padStart(2, '0')
      return `${hh}:${mm}:${ss}`
    }
  }
  return String(ts)
}
