/** 后台统一按东八区展示时间（接口多为 ISO UTC / 带偏移） */
const DISPLAY_TZ = 'Asia/Shanghai'

/**
 * @param value ISO 字符串、时间戳或 Date
 * @param withSeconds 是否包含秒
 */
export function formatDateTime(
  value: unknown,
  withSeconds = false,
): string {
  if (value == null || value === '') return '—'
  const d = value instanceof Date ? value : new Date(String(value))
  if (Number.isNaN(d.getTime())) return '—'

  // sv-SE → `yyyy-MM-dd HH:mm:ss`
  const raw = d.toLocaleString('sv-SE', { timeZone: DISPLAY_TZ, hour12: false })
  return withSeconds ? raw.slice(0, 19) : raw.slice(0, 16)
}
