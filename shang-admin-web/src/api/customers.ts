import { http, type ApiResult } from '@/utils/request'

export interface CustomerQuery {
  phone?: string
  nickname?: string
  status?: string
  clientType?: string
  page?: number
  pageSize?: number
}

export function fetchCustomers(params?: CustomerQuery): Promise<ApiResult<unknown>> {
  const { pageSize, ...rest } = params || {}
  return http({
    url: '/admin/customers',
    method: 'GET',
    params: { ...rest, size: pageSize },
  })
}

export function fetchCustomer(id: string | number): Promise<ApiResult<unknown>> {
  return http({ url: `/admin/customers/${id}`, method: 'GET' })
}

export function updateCustomerStatus(
  id: string | number,
  status: 'NORMAL' | 'FROZEN',
): Promise<ApiResult<unknown>> {
  return http({ url: `/admin/customers/${id}/status`, method: 'PUT', data: { status } })
}
