import { http } from '@/utils/request'

export interface SubmitOrderResult {
  orderId: string
  orderNo: string
  status: string
  goodsAmount: number
  freight: number
  payAmount: number
  expireAt: string
}

export function submitOrder(data: {
  addressId: string
  cartItemIds?: string[]
  skuId?: string
  quantity?: number
  idempotentKey: string
}) {
  return http<SubmitOrderResult>({ url: '/api/orders', method: 'POST', data })
}

export function fetchOrders(params?: { status?: string; page?: number; size?: number }) {
  return http<{ total: number; records: unknown[] }>({
    url: '/api/orders',
    method: 'GET',
    params,
  })
}

export function fetchOrderDetail(id: string) {
  return http<{
    order: Record<string, unknown>
    items: Array<Record<string, unknown>>
    shipments: Array<Record<string, unknown>>
  }>({ url: `/api/orders/${id}`, method: 'GET' })
}

export function cancelOrder(id: string) {
  return http<null>({ url: `/api/orders/${id}/cancel`, method: 'POST' })
}

export function confirmOrder(id: string) {
  return http<null>({ url: `/api/orders/${id}/confirm`, method: 'POST' })
}

export function fetchOrderLogistics(id: string) {
  return http<Array<Record<string, unknown>>>({
    url: `/api/orders/${id}/logistics`,
    method: 'GET',
  })
}

export function sandboxPay(orderId: string) {
  return http<{
    orderId: string
    status: string
    paidAt: string
    tradeNo: string
  }>({
    url: '/api/payment/sandbox',
    method: 'POST',
    data: { orderId },
  })
}

export function createReview(data: {
  orderItemId: string
  rating: number
  content?: string
  picKeys?: string[]
}) {
  return http<string>({ url: '/api/reviews', method: 'POST', data })
}

export function fetchAfterSales(params?: { status?: string; page?: number; size?: number }) {
  return http<{ total: number; records: unknown[] }>({
    url: '/api/after-sales',
    method: 'GET',
    params,
  })
}

export function fetchAfterSaleDetail(id: string) {
  return http<{ afterSale: Record<string, unknown>; order: Record<string, unknown> }>({
    url: `/api/after-sales/${id}`,
    method: 'GET',
  })
}

export function applyAfterSale(data: {
  orderId: string
  type: string
  reason: string
  evidenceKeys?: string[]
  refundAmount?: number
}) {
  return http<string>({ url: '/api/after-sales', method: 'POST', data })
}
