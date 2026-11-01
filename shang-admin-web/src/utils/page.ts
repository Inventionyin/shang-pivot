/** 后端分页：{ total, records }；兼容前端早期 list 字段 */
export function unwrapPage<T = unknown>(
  data: unknown,
): { total: number; records: T[] } | null {
  if (!data || typeof data !== 'object') return null
  const obj = data as { total?: number; records?: T[]; list?: T[] }
  const records = obj.records ?? obj.list
  if (!Array.isArray(records)) return null
  return { total: obj.total ?? records.length, records }
}

/** 兼容 list / records / 裸数组 */
export function unwrapList<T = unknown>(data: unknown): T[] {
  if (Array.isArray(data)) return data as T[]
  if (!data || typeof data !== 'object') return []
  const obj = data as { records?: T[]; list?: T[] }
  const list = obj.records ?? obj.list
  return Array.isArray(list) ? list : []
}
