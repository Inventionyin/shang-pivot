import { http } from '@/utils/request'
import type { ProductCard } from '@/api/catalog'

/** 对齐 Address 实体：name / phone / province… */
export interface AddressVO {
  id: string
  name: string
  phone: string
  province?: string
  city?: string
  district?: string
  detail: string
  isDefault?: boolean
}

export function fetchAddresses() {
  return http<AddressVO[]>({ url: '/api/addresses', method: 'GET' })
}

export function createAddress(data: Omit<AddressVO, 'id'> & { isDefault?: boolean }) {
  return http<string>({ url: '/api/addresses', method: 'POST', data })
}

export function updateAddress(id: string, data: Partial<AddressVO>) {
  return http<null>({ url: `/api/addresses/${id}`, method: 'PUT', data })
}

export function removeAddress(id: string) {
  return http<null>({ url: `/api/addresses/${id}`, method: 'DELETE' })
}

export function setDefaultAddress(id: string) {
  return http<null>({ url: `/api/addresses/${id}/default`, method: 'PUT' })
}

export function fetchFavorites(params?: { page?: number; size?: number }) {
  return http<{ total: number; records: ProductCard[] }>({
    url: '/api/favorites',
    method: 'GET',
    params,
  })
}

export function addFavorite(spuId: string) {
  return http<null>({ url: `/api/favorites/${spuId}`, method: 'POST' })
}

export function removeFavorite(spuId: string) {
  return http<null>({ url: `/api/favorites/${spuId}`, method: 'DELETE' })
}

export function fetchHistory(params?: { page?: number; size?: number }) {
  return http<{ total: number; records: ProductCard[] }>({
    url: '/api/history',
    method: 'GET',
    params,
  })
}

export function clearHistory() {
  return http<null>({ url: '/api/history', method: 'DELETE' })
}

export function uploadFile(file: File, bizType = 'AFTER_SALE') {
  const form = new FormData()
  form.append('file', file)
  form.append('bizType', bizType)
  return http<{ objectKey: string; url: string }>({
    url: '/api/files/upload',
    method: 'POST',
    data: form,
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}
