import { http, type ApiResult } from '@/utils/request'

export interface PermissionSavePayload {
  parentId?: number | string
  code: string
  name: string
  type: string
  path?: string
  component?: string
  icon?: string
  sort?: number
  visible?: boolean
  status?: string
}

export function fetchPermissions(): Promise<ApiResult<unknown>> {
  return http({ url: '/admin/permissions', method: 'GET' })
}

export function fetchPermissionTree(): Promise<ApiResult<unknown>> {
  return http({ url: '/admin/permissions/tree', method: 'GET' })
}

export function savePermission(
  data: PermissionSavePayload,
  id?: string | number,
): Promise<ApiResult<unknown>> {
  if (id) return http({ url: `/admin/permissions/${id}`, method: 'PUT', data })
  return http({ url: '/admin/permissions', method: 'POST', data })
}

export function deletePermission(id: string | number): Promise<ApiResult<unknown>> {
  return http({ url: `/admin/permissions/${id}`, method: 'DELETE' })
}
