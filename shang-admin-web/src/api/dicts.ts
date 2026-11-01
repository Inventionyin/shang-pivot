import { http, type ApiResult } from '@/utils/request'

export interface DictSavePayload {
  code?: string
  name: string
  remark?: string
}

export interface DictItemSavePayload {
  dictId: string | number
  label: string
  value: string
  sort?: number
  status?: string
}

export function fetchDicts(params?: {
  code?: string
  name?: string
  page?: number
  size?: number
}): Promise<ApiResult<unknown>> {
  return http({ url: '/admin/dicts', method: 'GET', params })
}

export function saveDict(
  data: DictSavePayload,
  id?: string | number,
): Promise<ApiResult<unknown>> {
  if (id) return http({ url: `/admin/dicts/${id}`, method: 'PUT', data })
  return http({ url: '/admin/dicts', method: 'POST', data })
}

export function deleteDict(id: string | number): Promise<ApiResult<unknown>> {
  return http({ url: `/admin/dicts/${id}`, method: 'DELETE' })
}

export function fetchDictItems(dictId: string | number): Promise<ApiResult<unknown>> {
  return http({ url: '/admin/dict-items', method: 'GET', params: { dictId } })
}

export function fetchDictItemsByCode(code: string): Promise<ApiResult<unknown>> {
  return http({ url: '/admin/dict-items/by-code', method: 'GET', params: { code } })
}

export function saveDictItem(
  data: DictItemSavePayload,
  id?: string | number,
): Promise<ApiResult<unknown>> {
  if (id) return http({ url: `/admin/dict-items/${id}`, method: 'PUT', data })
  return http({ url: '/admin/dict-items', method: 'POST', data })
}

export function deleteDictItem(id: string | number): Promise<ApiResult<unknown>> {
  return http({ url: `/admin/dict-items/${id}`, method: 'DELETE' })
}
