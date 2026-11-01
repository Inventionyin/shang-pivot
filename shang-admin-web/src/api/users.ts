import { http, type ApiResult } from '@/utils/request'

export interface UserQuery {
  username?: string
  phone?: string
  keyword?: string
  status?: string
  deptId?: string | number
  page?: number
  pageSize?: number
}

export interface UserSavePayload {
  username?: string
  password?: string
  realName?: string
  phone?: string
  email?: string
  deptId?: number | null
  status?: string
  roleIds?: number[]
}

export function fetchUsers(params?: UserQuery): Promise<ApiResult<unknown>> {
  const { keyword, ...rest } = params || {}
  return http({
    url: '/admin/users',
    method: 'GET',
    params: {
      ...rest,
      // 「关键字」一个输入框同时匹配用户名或手机号：两个都带上，
      // 后端 AdminUserService.page 用 OR 分组（PRD-V1 §8.2）。
      username: rest.username ?? keyword,
      phone: rest.phone ?? keyword,
    },
  })
}

export function fetchUser(id: string | number): Promise<ApiResult<unknown>> {
  return http({ url: `/admin/users/${id}`, method: 'GET' })
}

export function saveUser(data: UserSavePayload, id?: string | number): Promise<ApiResult<unknown>> {
  if (id) return http({ url: `/admin/users/${id}`, method: 'PUT', data })
  return http({ url: '/admin/users', method: 'POST', data })
}

export function updateUserStatus(
  id: string | number,
  status: string,
): Promise<ApiResult<unknown>> {
  return http({ url: `/admin/users/${id}/status`, method: 'PUT', data: { status } })
}

export function resetUserPassword(
  id: string | number,
  password: string,
): Promise<ApiResult<unknown>> {
  return http({ url: `/admin/users/${id}/password`, method: 'PUT', data: { password } })
}

export function deleteUser(id: string | number): Promise<ApiResult<unknown>> {
  return http({ url: `/admin/users/${id}`, method: 'DELETE' })
}

export function fetchRolesAll(): Promise<ApiResult<unknown>> {
  return http({ url: '/admin/roles/all', method: 'GET' })
}
