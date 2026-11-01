import { http } from '@/utils/request'

export interface CartItemVO {
  id?: string
  cartItemId: string
  skuId: string
  spuId?: string
  title: string
  pic?: string
  price: number
  quantity: number
  selected: boolean
  available?: boolean
  valid?: boolean
  availableStock?: number
  /** 失效原因（后端下发）：商品已下架 / 库存不足 / SKU 不存在 */
  invalidReason?: string
}

export interface CartVO {
  items: CartItemVO[]
  selectedAmount: number
  available: boolean
}

export function fetchCart() {
  return http<CartVO>({ url: '/api/cart', method: 'GET' })
}

export function addCartItem(skuId: string, quantity: number) {
  return http<{ cartItemId: string; quantity: number; selected: boolean }>({
    url: '/api/cart/items',
    method: 'POST',
    data: { skuId, quantity },
  })
}

export function updateCartItem(
  id: string,
  data: { quantity?: number; selected?: boolean },
) {
  return http<null>({ url: `/api/cart/items/${id}`, method: 'PUT', data })
}

export function removeCartItem(id: string) {
  return http<null>({ url: `/api/cart/items/${id}`, method: 'DELETE' })
}

export function clearInvalidCartItems() {
  return http<null>({ url: '/api/cart/clear-invalid', method: 'POST' })
}
