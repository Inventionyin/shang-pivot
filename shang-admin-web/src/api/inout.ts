import { http, type ApiResult } from '@/utils/request'

export interface InOutQuery {
  type?: string
  billNo?: string
  skuId?: number | string
  from?: string
  to?: string
  page?: number
  pageSize?: number
}

export interface InOutCreatePayload {
  type: 'PURCHASE_IN' | 'STOCKTAKE'
  warehouseId?: number
  remark?: string
  items: Array<{ skuId: number; qty: number; remark?: string }>
}

export function fetchInOutOrders(params?: InOutQuery): Promise<ApiResult<unknown>> {
  const { pageSize, ...rest } = params || {}
  return http({
    url: '/admin/in-out',
    method: 'GET',
    params: { ...rest, size: pageSize },
  })
}

export function createInOutOrder(data: InOutCreatePayload): Promise<ApiResult<number>> {
  return http({ url: '/admin/in-out', method: 'POST', data })
}
