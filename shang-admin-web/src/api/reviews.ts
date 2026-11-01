import { http, type ApiResult } from '@/utils/request'

export interface ReviewQuery {
  rating?: number
  /** 筛低星：如 ratingMax=2 表示 1～2 星 */
  ratingMax?: number
  status?: string
  keyword?: string
  page?: number
  pageSize?: number
}

export function fetchReviews(params?: ReviewQuery): Promise<ApiResult<unknown>> {
  const { pageSize, ...rest } = params || {}
  return http({
    url: '/admin/reviews',
    method: 'GET',
    params: { ...rest, size: pageSize },
  })
}

export function updateReviewStatus(
  id: string | number,
  status: 'VISIBLE' | 'HIDDEN',
): Promise<ApiResult<unknown>> {
  return http({ url: `/admin/reviews/${id}/status`, method: 'PUT', data: { status } })
}
