/** 展示用的格式化。全部对 `null` / `undefined` 宽容，返回占位符而不是 `null` 字样。 */

const PLACEHOLDER = '—'

/** epoch 毫秒 → `YYYY-MM-DD HH:mm:ss`（本机时区）。 */
export function formatTime(millis) {
  if (millis === null || millis === undefined || millis === '') return PLACEHOLDER
  const value = Number(millis)
  if (!Number.isFinite(value)) return String(millis)
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(millis)
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
    + `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/** 相对时间，用于「多久之前」这类概览信息。 */
export function relativeTime(millis) {
  if (!millis) return PLACEHOLDER
  const diff = Date.now() - Number(millis)
  if (!Number.isFinite(diff)) return PLACEHOLDER
  if (diff < 0) return formatTime(millis)
  const minute = 60_000
  const hour = 60 * minute
  const day = 24 * hour
  if (diff < minute) return '刚刚'
  if (diff < hour) return `${Math.floor(diff / minute)} 分钟前`
  if (diff < day) return `${Math.floor(diff / hour)} 小时前`
  if (diff < 30 * day) return `${Math.floor(diff / day)} 天前`
  return formatTime(millis)
}

export function formatBytes(value) {
  if (value === null || value === undefined || value === '') return PLACEHOLDER
  const bytes = Number(value)
  if (!Number.isFinite(bytes)) return String(value)
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB', 'PB']
  let size = bytes / 1024
  let index = 0
  while (size >= 1024 && index < units.length - 1) {
    size /= 1024
    index += 1
  }
  return `${size.toFixed(size >= 100 ? 0 : 1)} ${units[index]}`
}

export function formatNumber(value) {
  if (value === null || value === undefined || value === '') return PLACEHOLDER
  const number = Number(value)
  return Number.isFinite(number) ? number.toLocaleString('zh-CN') : String(value)
}

/** 空值统一显示为占位符。 */
export function orPlaceholder(value) {
  if (value === null || value === undefined || value === '') return PLACEHOLDER
  return value
}

/** 稳定、可读的 JSON 文本，用于展示任意结构。 */
export function prettyJson(value) {
  if (value === null || value === undefined) return ''
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

/** 把 `{a: '1', b: ''}` 这类对象渲染成 `a=1` 形式的一行文本。 */
export function inlineMap(map) {
  const entries = Object.entries(map || {})
  if (!entries.length) return PLACEHOLDER
  return entries.map(([key, value]) => `${key}=${value}`).join(', ')
}

/** 键值对编辑器（PropertiesEditor）在提交前把行数组收敛回对象。 */
export function rowsToMap(rows) {
  const result = {}
  for (const row of rows || []) {
    const key = (row.key || '').trim()
    if (!key) continue
    result[key] = row.value ?? ''
  }
  return result
}

export function mapToRows(map) {
  return Object.entries(map || {}).map(([key, value]) => ({ key, value: String(value ?? '') }))
}
