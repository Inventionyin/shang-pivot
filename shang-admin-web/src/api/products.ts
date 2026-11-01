import { http, type ApiResult } from '@/utils/request'

export interface ProductQuery {
  keyword?: string
  name?: string
  categoryId?: string | number
  status?: string
  page?: number
  pageSize?: number
}

export interface SkuSavePayload {
  title: string
  price: number
  costPrice?: number
  barCode?: string
  specs?: Record<string, string>
  pic?: string
  status?: string
  initStock?: number
  warehouseId?: number
  lowThreshold?: number
}

export interface ProductSavePayload {
  categoryId?: number | null
  brandId?: number | null
  name: string
  subtitle?: string
  pics?: string[]
  videos?: string[]
  detailHtml?: string
  status?: string
  /** ISO-8601 带偏移，如 2026-09-21T10:00:00+08:00 */
  scheduleOnAt?: string | null
  clearScheduleOnAt?: boolean
  skus?: SkuSavePayload[]
}

export function fetchProducts(params?: ProductQuery): Promise<ApiResult<unknown>> {
  const { keyword, pageSize, ...rest } = params || {}
  return http({
    url: '/admin/products',
    method: 'GET',
    params: {
      ...rest,
      name: rest.name ?? keyword,
      size: pageSize ?? (rest as { size?: number }).size,
    },
  })
}

export function fetchProduct(id: string | number): Promise<ApiResult<unknown>> {
  return http({ url: `/admin/products/${id}`, method: 'GET' })
}

export function saveProduct(
  data: ProductSavePayload,
  id?: string | number,
): Promise<ApiResult<unknown>> {
  if (id) {
    return http({ url: `/admin/products/${id}`, method: 'PUT', data })
  }
  return http({ url: '/admin/products', method: 'POST', data })
}

export function deleteProduct(id: string | number): Promise<ApiResult<unknown>> {
  return http({ url: `/admin/products/${id}`, method: 'DELETE' })
}

export function onShelf(id: string | number): Promise<ApiResult<unknown>> {
  return http({ url: `/admin/products/${id}/on`, method: 'PUT' })
}

export function offShelf(id: string | number): Promise<ApiResult<unknown>> {
  return http({ url: `/admin/products/${id}/off`, method: 'PUT' })
}

export function batchOffShelf(ids: Array<string | number>): Promise<ApiResult<number>> {
  return http({
    url: '/admin/products/batch-off',
    method: 'PUT',
    data: ids.map((id) => Number(id)),
  })
}
