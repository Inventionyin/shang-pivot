import { http, type ApiResult } from '@/utils/request'
import request from '@/utils/request'

export interface OrderQuery {
  status?: string
  orderNo?: string
  customerName?: string
  keyword?: string
  phoneTail?: string
  clientType?: string
  createdFrom?: string
  createdTo?: string
  page?: number
  pageSize?: number
}

export function fetchOrders(params?: OrderQuery): Promise<ApiResult<unknown>> {
  const { keyword, pageSize, ...rest } = params || {}
  const orderNo = rest.orderNo ?? keyword
  return http({
    url: '/admin/orders',
    method: 'GET',
    params: {
      ...rest,
      orderNo: orderNo || undefined,
      size: pageSize,
    },
  })
}

export function fetchOrder(id: string | number): Promise<ApiResult<unknown>> {
  return http({ url: `/admin/orders/${id}`, method: 'GET' })
}

export function shipOrder(
  id: string | number,
  data: { trackingNo: string; carrier?: string },
): Promise<ApiResult<unknown>> {
  return http({ url: `/admin/orders/${id}/ship`, method: 'POST', data })
}

export async function exportOrders(params?: OrderQuery): Promise<void> {
  const { keyword, pageSize: _ps, page: _p, ...rest } = params || {}
  const res = await request.request<Blob>({
    url: '/admin/orders/export',
    method: 'GET',
    params: {
      ...rest,
      orderNo: rest.orderNo ?? keyword,
    },
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
    : `orders-${new Date().toISOString().slice(0, 10)}.csv`
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}
