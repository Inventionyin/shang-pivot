import { http, type ApiResult } from '@/utils/request'

export interface HomeSlotSummary {
  id: string | number
  code: string
  title: string
  status: string
  sort: number
  validFrom?: string | null
  validTo?: string | null
  itemCount?: number
}

export interface HomeSlotItem {
  id?: string | number
  sort?: number
  spuId?: string | number | null
  spuName?: string
  spuStatus?: string
  pic?: string | null
  price?: number | string | null
  soldCount?: number
  imageKey?: string | null
  linkType?: string
  linkValue?: string | null
  title?: string | null
  extra?: Record<string, unknown> | null
  status?: string
}

export interface HomeSlotDetail {
  id: string | number
  code: string
  title: string
  status: string
  sort: number
  validFrom?: string | null
  validTo?: string | null
  items: HomeSlotItem[]
}

export interface HomeSlotSavePayload {
  title?: string
  status?: string
  sort?: number
  validFrom?: string | null
  validTo?: string | null
  items?: Array<{
    sort?: number
    spuId?: number | null
    imageKey?: string | null
    linkType?: string
    linkValue?: string | null
    title?: string | null
    extra?: Record<string, unknown> | null
    status?: string
  }>
}

export function fetchHomeSlots(): Promise<ApiResult<HomeSlotSummary[]>> {
  return http({ url: '/admin/home/slots', method: 'GET' })
}

export function fetchHomeSlot(code: string): Promise<ApiResult<HomeSlotDetail>> {
  return http({ url: `/admin/home/slots/${encodeURIComponent(code)}`, method: 'GET' })
}

export function saveHomeSlot(
  code: string,
  data: HomeSlotSavePayload,
): Promise<ApiResult<HomeSlotDetail>> {
  return http({
    url: `/admin/home/slots/${encodeURIComponent(code)}`,
    method: 'PUT',
    data,
  })
}
