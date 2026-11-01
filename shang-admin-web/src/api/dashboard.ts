import { http, type ApiResult } from '@/utils/request'

export function fetchDashboardStats(): Promise<ApiResult<unknown>> {
  return http({ url: '/admin/dashboard/stats', method: 'GET' })
}

export function fetchLowStockPreview(): Promise<ApiResult<unknown>> {
  // 工作台表格同时展示「低库存」与「关注」两档
  return http({ url: '/admin/stocks', method: 'GET', params: { warnOnly: true, pageSize: 5 } })
}
