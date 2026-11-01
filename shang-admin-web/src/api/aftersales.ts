import { http, type ApiResult } from '@/utils/request'

export interface AfterSaleQuery {
  status?: string
  type?: string
  afterSaleNo?: string
  page?: number
  pageSize?: number
}

export function fetchAfterSales(params?: AfterSaleQuery): Promise<ApiResult<unknown>> {
  return http({ url: '/admin/after-sales', method: 'GET', params })
}

export function fetchAfterSale(id: string | number): Promise<ApiResult<unknown>> {
  return http({ url: `/admin/after-sales/${id}`, method: 'GET' })
}

export function auditAfterSale(
  id: string | number,
  data: { approved: boolean; remark?: string },
): Promise<ApiResult<unknown>> {
  return http({ url: `/admin/after-sales/${id}/audit`, method: 'POST', data })
}
