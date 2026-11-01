import request, { http, type ApiResult } from '@/utils/request'

export interface LogQuery {
  username?: string
  keyword?: string
  module?: string
  from?: string
  to?: string
  page?: number
  pageSize?: number
}

/** 与列表展示、筛选下拉共用的模块字典（value=入库码，label=展示名） */
export const LOG_MODULE_OPTIONS = [
  { value: 'auth', label: '认证' },
  { value: 'user', label: '用户' },
  { value: 'role', label: '角色' },
  { value: 'permission', label: '权限' },
  { value: 'dept', label: '部门' },
  { value: 'dict', label: '字典' },
  { value: 'config', label: '系统配置' },
  { value: 'file', label: '文件' },
  { value: 'product', label: '商品' },
  { value: 'category', label: '类目' },
  { value: 'brand', label: '品牌' },
  { value: 'attribute', label: '属性' },
  { value: 'stock', label: '库存' },
  { value: 'warehouse', label: '仓库' },
  { value: 'order', label: '订单' },
  { value: 'aftersale', label: '售后' },
] as const

const MODULE_LABEL_MAP: Record<string, string> = Object.fromEntries(
  LOG_MODULE_OPTIONS.map((o) => [o.value, o.label]),
)

export function formatLogModule(module?: string | null): string {
  if (!module) return '—'
  return MODULE_LABEL_MAP[module] ?? module
}

function toListParams(params?: LogQuery) {
  const { keyword, ...rest } = params || {}
  return {
    ...rest,
    username: rest.username ?? keyword,
  }
}

export function fetchOperationLogs(params?: LogQuery): Promise<ApiResult<unknown>> {
  return http({
    url: '/admin/operation-logs',
    method: 'GET',
    params: toListParams(params),
  })
}

export function fetchOperationLog(id: string | number): Promise<ApiResult<unknown>> {
  return http({
    url: `/admin/operation-logs/${id}`,
    method: 'GET',
  })
}

/** 导出 CSV：绕过 JSON 包装，直接下载文件流 */
export async function exportOperationLogs(params?: LogQuery): Promise<void> {
  const res = await request.request<Blob>({
    url: '/admin/operation-logs/export',
    method: 'GET',
    params: toListParams(params),
    responseType: 'blob',
  })

  const blob = res.data
  if (blob.type && blob.type.includes('application/json')) {
    const text = await blob.text()
    let msg = '导出失败'
    try {
      const json = JSON.parse(text) as { msg?: string }
      if (json.msg) msg = json.msg
    } catch {
      /* ignore */
    }
    throw new Error(msg)
  }

  const disposition = String(res.headers['content-disposition'] || '')
  const matched = /filename\*?=(?:UTF-8''|")?([^";]+)/i.exec(disposition)
  const rawName = matched?.[1]
  const filename = rawName
    ? decodeURIComponent(rawName.replace(/"/g, ''))
    : `operation-logs-${new Date().toISOString().slice(0, 10)}.csv`

  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}
