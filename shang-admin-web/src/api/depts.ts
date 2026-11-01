import { http, type ApiResult } from '@/utils/request'

export interface DeptSavePayload {
  name: string
  parentId?: number | string
  sort?: number
  status?: string
}

export function fetchDeptTree(): Promise<ApiResult<unknown>> {
  return http({ url: '/admin/depts/tree', method: 'GET' })
}

export function fetchDepts(): Promise<ApiResult<unknown>> {
  return http({ url: '/admin/depts', method: 'GET' })
}

export function saveDept(
  data: DeptSavePayload,
  id?: string | number,
): Promise<ApiResult<unknown>> {
  if (id) return http({ url: `/admin/depts/${id}`, method: 'PUT', data })
  return http({ url: '/admin/depts', method: 'POST', data })
}

export function deleteDept(id: string | number): Promise<ApiResult<unknown>> {
  return http({ url: `/admin/depts/${id}`, method: 'DELETE' })
}
