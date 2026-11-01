import { http, type ApiResult } from '@/utils/request'

export interface SiteBrand {
  logoKey?: string | null
  logoUrl?: string | null
  name: string
  slogan?: string | null
  faviconKey?: string | null
  faviconUrl?: string | null
}

export function fetchSiteBrand(): Promise<ApiResult<SiteBrand>> {
  return http({ url: '/admin/site/brand', method: 'GET' })
}

export function updateSiteBrand(data: Partial<SiteBrand>): Promise<ApiResult<SiteBrand>> {
  return http({ url: '/admin/site/brand', method: 'PUT', data })
}
