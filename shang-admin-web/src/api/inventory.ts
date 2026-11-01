import { http, type ApiResult } from '@/utils/request'

export interface StockQuery {
  skuId?: number
  warehouseId?: number
  /** 仅低库存（可售 ≤ 阈值） */
  lowOnly?: boolean
  /** 预警档：低库存 + 关注（可售 ≤ 阈值 × 1.5） */
  warnOnly?: boolean
  page?: number
  pageSize?: number
}

export interface StockAdjustPayload {
  skuId: number
  warehouseId?: number
  changeQty: number
  reason?: string
}

export function fetchStocks(params?: StockQuery): Promise<ApiResult<unknown>> {
  return http({ url: '/admin/stocks', method: 'GET', params })
}

export function adjustStock(data: StockAdjustPayload): Promise<ApiResult<unknown>> {
  return http({ url: '/admin/stocks/adjust', method: 'PUT', data })
}
