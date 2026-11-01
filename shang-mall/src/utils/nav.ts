import type { Router } from 'vue-router'
import { showToast } from 'vant'

/** 后台运营位条目的跳转类型（与 HomeSlotItem.linkType 对齐） */
export type SlotLink = {
  linkType?: string | null
  linkValue?: string | null
  spuId?: string | number | null
}

/**
 * 按后台配置的 linkType 跳转：
 * - SPU → 商品详情
 * - CATEGORY → 分类页
 * - URL → 站内 path 用路由，外链新开窗口
 */
export function navigateSlotLink(router: Router, item?: SlotLink | null): boolean {
  if (!item) return false
  const type = String(item.linkType || '').toUpperCase()
  const value = item.linkValue != null ? String(item.linkValue).trim() : ''
  const spuId = item.spuId != null && item.spuId !== '' ? String(item.spuId) : ''

  if (type === 'SPU' || (!type && spuId) || (spuId && type !== 'URL' && type !== 'CATEGORY')) {
    const id = spuId || value
    if (!id) {
      showToast('商品链接无效')
      return false
    }
    void router.push(`/product/${id}`)
    return true
  }

  if (type === 'CATEGORY') {
    if (!value) {
      showToast('类目链接无效')
      return false
    }
    void router.push({ path: '/category', query: { categoryId: value } })
    return true
  }

  if (type === 'URL') {
    if (!value) {
      showToast('链接未配置')
      return false
    }
    if (value.startsWith('/')) {
      void router.push(value)
      return true
    }
    if (/^https?:\/\//i.test(value)) {
      window.open(value, '_blank', 'noopener,noreferrer')
      return true
    }
    showToast('链接格式不正确')
    return false
  }

  if (spuId) {
    void router.push(`/product/${spuId}`)
    return true
  }

  showToast('该项暂未配置跳转')
  return false
}
