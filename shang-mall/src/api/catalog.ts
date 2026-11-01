import { http } from '@/utils/request'

export interface SiteBrand {
  logoUrl?: string | null
  logoKey?: string | null
  name: string
  slogan?: string | null
  faviconUrl?: string | null
}

export interface HomeSlotItem {
  imageKey?: string | null
  imageUrl?: string | null
  linkType?: string
  linkValue?: string | null
  title?: string | null
  spuId?: string | number
  name?: string
  pic?: string | null
  price?: number
  soldCount?: number
}

export interface HomeSlot {
  code: string
  title: string
  items: HomeSlotItem[]
}

export interface HomeVO {
  brand?: SiteBrand
  slots?: HomeSlot[]
  feed?: ProductCard[]
}

export interface CategoryNode {
  id: string | number
  name: string
  children?: CategoryNode[]
}

/** 对齐后端 CatalogReadService.toCard */
export interface ProductCard {
  spuId: string | number
  name: string
  subtitle?: string | null
  pic?: string | null
  price: number
  priceMax?: number | null
  soldCount?: number
}

export interface ProductSku {
  skuId: string | number
  title: string
  price: number
  specs?: Record<string, string>
  pic?: string | null
  availableStock: number
}

export interface ProductDetailVO {
  spu: {
    id: string | number
    name: string
    subtitle?: string
    pics?: string[]
    detailHtml?: string
    soldCount?: number
  }
  skus: ProductSku[]
  reviewsPreview?: Array<{
    id: string | number
    rating: number
    content?: string
    pics?: string[]
    createdAt?: string
  }>
  soldCount?: number
  favorited?: boolean
}

export function fetchSiteBrand() {
  return http<SiteBrand>({ url: '/api/site/brand', method: 'GET' })
}

export function fetchHome(feedSize = 20) {
  return http<HomeVO>({ url: '/api/home', method: 'GET', params: { feedSize } })
}

export function fetchCategories() {
  return http<CategoryNode[]>({ url: '/api/categories', method: 'GET' })
}

export function fetchProducts(params: {
  categoryId?: string
  page?: number
  size?: number
  sort?: string
}) {
  return http<{ total: number; records: ProductCard[] }>({
    url: '/api/products',
    method: 'GET',
    params,
  })
}

export function fetchProductDetail(spuId: string) {
  return http<ProductDetailVO>({ url: `/api/products/${spuId}`, method: 'GET' })
}

/**
 * 收藏 / 足迹等列表判定「已下架 / 已售罄」：详情接口对已下架商品返回错误，
 * 上架商品返回各规格可售库存。静默失败，由调用方决定置灰展示。
 */
export async function fetchSpuAvailability(spuId: string): Promise<boolean> {
  try {
    const res = await http<ProductDetailVO>({
      url: `/api/products/${spuId}`,
      method: 'GET',
      silent: true,
    })
    return (res.data.skus || []).some((sku) => (sku.availableStock ?? 0) > 0)
  } catch {
    return false
  }
}

export function searchProducts(params: { q: string; page?: number; size?: number }) {
  return http<{ total: number; records: ProductCard[] }>({
    url: '/api/search',
    method: 'GET',
    params,
  })
}

export function fetchProductReviews(spuId: string, params?: { page?: number; size?: number }) {
  return http<{ total: number; records: unknown[] }>({
    url: `/api/products/${spuId}/reviews`,
    method: 'GET',
    params,
  })
}
