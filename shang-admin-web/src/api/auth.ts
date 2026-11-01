import { http, type ApiResult } from '@/utils/request'

export interface LoginPayload {
  username: string
  password: string
}

export interface MenuNode {
  id: number | string
  parentId?: number | string
  code: string
  name: string
  type: string
  path?: string
  component?: string
  icon?: string
  sort?: number
  visible?: boolean
  children?: MenuNode[]
}

export interface LoginUserInfo {
  id: number | string
  username: string
  realName?: string
  roles?: string[]
  permissions?: string[]
  menus?: MenuNode[]
}

/** 与后端 LoginVO 对齐 */
export interface LoginResult {
  accessToken: string
  refreshToken?: string
  expiresIn?: number
  user: LoginUserInfo
}

export function login(data: LoginPayload): Promise<ApiResult<LoginResult>> {
  return http<LoginResult>({
    url: '/admin/auth/login',
    method: 'POST',
    data,
  })
}

export function logout(): Promise<ApiResult<null>> {
  return http<null>({
    url: '/admin/auth/logout',
    method: 'POST',
  })
}

export function fetchMe(): Promise<ApiResult<LoginUserInfo>> {
  return http<LoginUserInfo>({
    url: '/admin/auth/me',
    method: 'GET',
  })
}
