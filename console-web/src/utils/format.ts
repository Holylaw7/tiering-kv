export function formatBytes(bytes?: number): string {
  if (!bytes || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit++
  }
  return `${value.toFixed(unit === 0 ? 0 : 2)} ${units[unit]}`
}

export function formatNumber(value?: number, digits = 0): string {
  if (value === undefined || value === null || Number.isNaN(value)) return '0'
  return value.toLocaleString('zh-CN', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits
  })
}

export function formatPercent(value?: number): string {
  if (value === undefined || value === null || Number.isNaN(value)) return '0%'
  return (value * 100).toFixed(1) + '%'
}

export function formatUptime(seconds?: number): string {
  if (!seconds || seconds <= 0) return '—'
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  if (days > 0) return `${days} 天 ${hours} 小时`
  if (hours > 0) return `${hours} 小时 ${minutes} 分`
  return `${minutes} 分 ${seconds % 60} 秒`
}

export function formatTtl(ttl?: number): string {
  if (ttl === undefined || ttl === null) return '—'
  if (ttl === -1) return '永久'
  if (ttl === -2) return '已过期'
  if (ttl >= 86400) return `${Math.floor(ttl / 86400)} 天`
  if (ttl >= 3600) return `${Math.floor(ttl / 3600)} 小时`
  if (ttl >= 60) return `${Math.floor(ttl / 60)} 分 ${ttl % 60} 秒`
  return `${ttl} 秒`
}

export function shortTime(raw?: string): string {
  if (!raw) return '—'
  return raw.length > 19 ? raw.slice(11, 19) : raw
}
