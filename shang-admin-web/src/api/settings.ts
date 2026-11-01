import { http, type ApiResult } from '@/utils/request'

export function fetchSystemConfig(params?: {
  category?: string
}): Promise<ApiResult<unknown>> {
  return http({ url: '/admin/system-configs', method: 'GET', params })
}

export function updateSystemConfig(
  id: string | number,
  data: { configValue?: string; remark?: string },
): Promise<ApiResult<unknown>> {
  return http({ url: `/admin/system-configs/${id}`, method: 'PUT', data })
}
