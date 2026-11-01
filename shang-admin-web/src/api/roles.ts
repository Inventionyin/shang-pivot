import { http, type ApiResult } from '@/utils/request'

export interface RoleSavePayload {
  code?: string
  name: string
  dataScope?: string
  status?: string
  remark?: string
  permissionIds?: Array<number | string>
}

export function fetchRoles(params?: {
  name?: string
  code?: string
  page?: number
  pageSize?: number
}): Promise<ApiResult<unknown>> {
  return http({ url: '/admin/roles', method: 'GET', params })
}

export function fetchRolesAll(): Promise<ApiResult<unknown>> {
  return http({ url: '/admin/roles/all', method: 'GET' })
}

export function saveRole(
  data: RoleSavePayload,
  id?: string | number,
): Promise<ApiResult<unknown>> {
  if (id) return http({ url: `/admin/roles/${id}`, method: 'PUT', data })
  return http({ url: '/admin/roles', method: 'POST', data })
}

export function deleteRole(id: string | number): Promise<ApiResult<unknown>> {
  return http({ url: `/admin/roles/${id}`, method: 'DELETE' })
}

/** 通过更新角色绑定权限（后端无独立 permissions 路由） */
export function bindRolePermissions(
  id: string | number,
  permissionIds: Array<number | string>,
  roleMeta?: Partial<RoleSavePayload>,
): Promise<ApiResult<unknown>> {
  return saveRole(
    {
      name: roleMeta?.name || '',
      dataScope: roleMeta?.dataScope,
      status: roleMeta?.status,
      remark: roleMeta?.remark,
      permissionIds,
    },
    id,
  )
}
