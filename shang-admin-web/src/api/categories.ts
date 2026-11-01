import { http, type ApiResult } from '@/utils/request'

export function fetchCategories(): Promise<ApiResult<unknown>> {
  return http({ url: '/admin/categories', method: 'GET' })
}

export function fetchCategoryTree(): Promise<ApiResult<unknown>> {
  return http({ url: '/admin/categories/tree', method: 'GET' })
}

export function saveCategory(
  data: { name: string; parentId?: number; sort?: number; status?: string; level?: number },
  id?: string | number,
): Promise<ApiResult<unknown>> {
  if (id) return http({ url: `/admin/categories/${id}`, method: 'PUT', data })
  return http({ url: '/admin/categories', method: 'POST', data })
}

export function deleteCategory(id: string | number): Promise<ApiResult<unknown>> {
  return http({ url: `/admin/categories/${id}`, method: 'DELETE' })
}

export function fetchBrands(params?: {
  name?: string
  page?: number
  pageSize?: number
  categoryId?: string | number
}): Promise<ApiResult<unknown>> {
  return http({ url: '/admin/brands', method: 'GET', params })
}

export function saveBrand(
  data: { name: string; logo?: string; sort?: number; status?: string; categoryId?: number },
  id?: string | number,
): Promise<ApiResult<unknown>> {
  if (id) return http({ url: `/admin/brands/${id}`, method: 'PUT', data })
  return http({ url: '/admin/brands', method: 'POST', data })
}

export function deleteBrand(id: string | number): Promise<ApiResult<unknown>> {
  return http({ url: `/admin/brands/${id}`, method: 'DELETE' })
}

export function fetchAttributes(params?: {
  categoryId?: string | number
}): Promise<ApiResult<unknown>> {
  return http({ url: '/admin/attributes', method: 'GET', params })
}

export function saveAttribute(
  data: {
    name: string
    type?: string
    options?: string[]
    sort?: number
    categoryId?: number | null
  },
  id?: string | number,
): Promise<ApiResult<unknown>> {
  if (id) return http({ url: `/admin/attributes/${id}`, method: 'PUT', data })
  return http({ url: '/admin/attributes', method: 'POST', data })
}

export function deleteAttribute(id: string | number): Promise<ApiResult<unknown>> {
  return http({ url: `/admin/attributes/${id}`, method: 'DELETE' })
}
