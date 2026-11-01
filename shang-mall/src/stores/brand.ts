import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { resolveMediaUrl } from '@/utils/media'
import { fetchSiteBrand, type SiteBrand } from '@/api/catalog'

/**
 * C 端默认 Logo：docs/02-design/brand/logos.png（购物袋形象）
 * 管理端 Logo：docs/02-design/brand/logo.png（购物车 + ShangPivot）— 禁止混用
 * 运行时以后台「站点品牌」配置优先；未配置或误配商品图时回退本地 logos.png
 */
const FALLBACK: SiteBrand = {
  name: '尚品汇',
  slogan: '选好物，享生活',
  logoUrl: '/logos.png',
}

/** 商品图 / 管理端标误配成 C 端 Logo 时回退 */
function isValidBrandLogo(key?: string | null): boolean {
  if (!key) return false
  const k = key.trim()
  if (!k) return false
  if (k.startsWith('PRODUCT/') || /\/seed\/product_/i.test(k)) return false
  if (k.includes('product_1.jpg')) return false
  return true
}

export const useBrandStore = defineStore('brand', () => {
  const brand = ref<SiteBrand>({ ...FALLBACK })
  const loaded = ref(false)

  const displayName = computed(() => brand.value.name || FALLBACK.name)
  const slogan = computed(() => brand.value.slogan || FALLBACK.slogan)
  const logoUrl = computed(() => {
    const raw = brand.value.logoUrl || brand.value.logoKey
    if (isValidBrandLogo(raw)) {
      const url = resolveMediaUrl(raw)
      // 后台若仍指向本地 /logo.png（管理端标），C 端改为 logos.png
      if (url === '/logo.png' || raw === '/logo.png' || raw === 'logo.png') {
        return FALLBACK.logoUrl!
      }
      return url || FALLBACK.logoUrl!
    }
    return FALLBACK.logoUrl!
  })

  async function load() {
    try {
      const res = await fetchSiteBrand()
      brand.value = { ...FALLBACK, ...res.data }
      if (brand.value.name) {
        document.title = brand.value.name
      }
    } catch {
      brand.value = { ...FALLBACK }
    } finally {
      loaded.value = true
    }
  }

  return { brand, loaded, displayName, slogan, logoUrl, load }
})
