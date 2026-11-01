<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { fetchProductDetail, type ProductDetailVO, type ProductSku } from '@/api/catalog'
import { addCartItem } from '@/api/cart'
import { addFavorite, removeFavorite } from '@/api/customer'
import { formatPrice } from '@/utils/format'
import { resolveMediaUrl } from '@/utils/media'
import { useClientType } from '@/composables/useClientType'
import { openLoginLayer } from '@/composables/useLoginLayer'
import { useCartStore } from '@/stores/cart'

interface SpecOption {
  value: string
  label: string
  outOfStock: boolean
}

interface SpecDimension {
  name: string
  options: SpecOption[]
}

/** PD-02 未选规格 / PD-03 库存不足：居中对话框（Web）或半屏层（H5） */
interface Notice {
  tone: 'warn' | 'danger'
  title: string
  desc: string
  primary: string
  gotoSpec?: boolean
}

const route = useRoute()
const router = useRouter()
const cart = useCartStore()
const { isH5, isWeb } = useClientType()

const detail = ref<ProductDetailVO | null>(null)
const loading = ref(true)
const favorited = ref(false)
const activePicIndex = ref(0)

/** 规格选择：默认不预选，保证「未选规格」态可达（PD-02） */
const selected = ref<Record<string, string>>({})
const qty = ref(1)
const specHighlight = ref(false)
const specError = ref(false)

const layerOpen = ref(false)
const notice = ref<Notice | null>(null)
const noticeOpen = ref(false)
const cartOkOpen = ref(false)
const buyOkOpen = ref(false)
const submitting = ref(false)

const spu = computed(() => detail.value?.spu)
/** 弹层本体透明，外观完全交给内部面板（Web 居中对话框 / H5 半屏） */
const popupStyle = { background: 'transparent' }
const skus = computed<ProductSku[]>(() => detail.value?.skus ?? [])
const pics = computed(() => (spu.value?.pics ?? []).filter(Boolean))
const activePic = computed(() => pics.value[activePicIndex.value] || '')
const soldCount = computed(() => Number(detail.value?.soldCount ?? spu.value?.soldCount ?? 0))

function matchPick(sku: ProductSku, pick: Record<string, string>): boolean {
  return Object.entries(pick).every(
    ([key, value]) => !value || String(sku.specs?.[key] ?? '') === value,
  )
}

function specsText(sku: ProductSku | undefined): string {
  if (!sku) return ''
  const values = Object.values(sku.specs ?? {})
  return values.length ? values.join(' · ') : sku.title
}

const dimensions = computed<SpecDimension[]>(() => {
  const names: string[] = []
  for (const sku of skus.value) {
    for (const name of Object.keys(sku.specs ?? {})) {
      if (!names.includes(name)) names.push(name)
    }
  }
  return names.map((name) => {
    const values = skus.value
      .map((sku) => sku.specs?.[name])
      .filter((v): v is string => typeof v === 'string' && v.length > 0)
    const options = [...new Set(values)].map((value) => {
      const matched = skus.value.filter((sku) => matchPick(sku, { ...selected.value, [name]: value }))
      const minPrice = matched.length ? Math.min(...matched.map((sku) => sku.price)) : null
      const outOfStock = !matched.some((sku) => (sku.availableStock ?? 0) > 0)
      return { value, label: optionLabel(value, outOfStock, minPrice), outOfStock }
    })
    return { name, options }
  })
})

const hasSpecs = computed(() => dimensions.value.length > 0)
const allChosen = computed(() => dimensions.value.every((d) => !!selected.value[d.name]))
const contextSkus = computed(() => skus.value.filter((sku) => matchPick(sku, selected.value)))
const matchedSku = computed<ProductSku | undefined>(() => {
  if (!hasSpecs.value) return skus.value[0]
  if (!allChosen.value) return undefined
  return skus.value.find((sku) => matchPick(sku, selected.value))
})

const priceMin = computed(() => (skus.value.length ? Math.min(...skus.value.map((s) => s.price)) : 0))
const priceMax = computed(() => (skus.value.length ? Math.max(...skus.value.map((s) => s.price)) : 0))
/** 当前展示价：命中规格取该规格价，未选完取当前上下文最低价 */
const displayPrice = computed(() => {
  if (matchedSku.value) return matchedSku.value.price
  if (contextSkus.value.length) return Math.min(...contextSkus.value.map((s) => s.price))
  return priceMin.value
})
const priceText = computed(() => {
  if (matchedSku.value) return `¥${formatPrice(matchedSku.value.price)}`
  if (priceMax.value > priceMin.value) {
    return `¥${formatPrice(priceMin.value)} ~ ¥${formatPrice(priceMax.value)}`
  }
  return `¥${formatPrice(priceMin.value)}`
})

function optionLabel(value: string, outOfStock: boolean, minPrice: number | null): string {
  if (outOfStock) return `${value} · 缺货`
  if (minPrice != null && minPrice !== displayPrice.value) return `${value} · ¥${formatPrice(minPrice)}`
  return value
}

/** 可售上限：命中规格用该规格库存，未选完用当前上下文最大可售 */
const maxQty = computed(() => {
  if (matchedSku.value) return Math.max(0, matchedSku.value.availableStock ?? 0)
  if (!contextSkus.value.length) return 0
  return Math.max(...contextSkus.value.map((sku) => Math.max(0, sku.availableStock ?? 0)))
})

const spuSoldOut = computed(
  () => skus.value.length > 0 && skus.value.every((sku) => (sku.availableStock ?? 0) <= 0),
)
/** 数量停在可售上限时继续加：PD-03；命中规格可售为 0：PD-04 */
const selectedUnavailable = computed(() => {
  if (!hasSpecs.value) return spuSoldOut.value
  if (!allChosen.value) return false
  return (matchedSku.value?.availableStock ?? 0) <= 0
})
/** PD-04：可切换的有货规格 */
const alternatives = computed(() => {
  const out: string[] = []
  for (const dim of dimensions.value) {
    for (const option of dim.options) {
      if (selected.value[dim.name] === option.value) continue
      if (option.outOfStock || out.includes(option.value)) continue
      out.push(option.value)
    }
  }
  return out
})

const selectedText = computed(() => {
  const values = dimensions.value
    .map((d) => selected.value[d.name])
    .filter((v): v is string => !!v)
  return values.length ? values.join(' · ') : '未选择'
})

const stockHint = computed(() =>
  matchedSku.value ? `库存 ${maxQty.value} 件可售` : '选择规格后显示该规格可售库存',
)
const limitHint = computed(() =>
  matchedSku.value
    ? `单笔最多可购 ${maxQty.value} 件，超出会提示「库存不足」`
    : '选择规格后显示该规格可售库存',
)

const chosenSku = computed<ProductSku | undefined>(() => matchedSku.value)
const goodsAmount = computed(() => (chosenSku.value?.price ?? 0) * qty.value)
/** PD-05：购物车商品件数与商品金额（有效商品合计） */
const cartAmount = computed(() =>
  cart.items.reduce((sum, item) => (item.available ? sum + item.price * item.quantity : sum), 0),
)

watch(maxQty, (max) => {
  if (max > 0 && qty.value > max) qty.value = max
})

onMounted(async () => {
  const id = String(route.params.spuId || '')
  try {
    const res = await fetchProductDetail(id)
    detail.value = res.data
    favorited.value = !!res.data.favorited
  } catch {
    detail.value = null
  } finally {
    loading.value = false
  }
})

function ensureLogin(): Promise<boolean> {
  // PRD §8.5：未登录点加购 / 购买 → 登录层，成功后回本页并保留已选规格
  return openLoginLayer()
}

/** PD-02 文案：商品有哪几组规格 */
function specWarnDesc(): string {
  const names = dimensions.value.map((d) => d.name)
  const join = names.length === 2 ? names.join('与') : names.join('、')
  const group = ['', '一组', '两组', '三组'][names.length] ?? `${names.length} 组`
  return `该商品有${join}${group}规格，选好后才能加入购物车或立即购买。`
}

function openLayer() {
  noticeOpen.value = false
  specHighlight.value = false
  specError.value = false
  layerOpen.value = true
}

function openNotice(next: Notice) {
  notice.value = next
  noticeOpen.value = true
}

/** 详情页「加入购物车 / 立即购买」：未选规格先提示，否则进入规格层 */
function onPageAction() {
  if (spuSoldOut.value) return
  if (!hasSpecs.value || allChosen.value) {
    openLayer()
    return
  }
  specHighlight.value = true
  openNotice({
    tone: 'warn',
    title: '请选择规格',
    desc: specWarnDesc(),
    primary: '去选择规格',
    gotoSpec: true,
  })
}

function onNoticePrimary() {
  const gotoSpec = notice.value?.gotoSpec
  noticeOpen.value = false
  if (gotoSpec) openLayer()
}

function onChip(dim: string, value: string) {
  selected.value = { ...selected.value, [dim]: value }
  if (layerOpen.value) {
    specHighlight.value = false
    return
  }
  openLayer()
}

function onQtyMinus() {
  if (qty.value > 1) qty.value -= 1
}

function onQtyPlus() {
  if (maxQty.value <= 0) return
  if (qty.value >= maxQty.value) {
    openNotice({
      tone: 'danger',
      title: '库存不足',
      desc: `该规格当前可售 ${maxQty.value} 件，数量已停在最大可售数。`,
      primary: '知道了',
    })
    return
  }
  qty.value += 1
}

async function onLayerAction(action: 'cart' | 'buy') {
  if (selectedUnavailable.value || submitting.value) return
  if (hasSpecs.value && !allChosen.value) {
    specError.value = true
    specHighlight.value = true
    return
  }
  const sku = chosenSku.value
  if (!sku) return
  // 设计系统 §4.2 规则 4：弹层不叠加 —— 先关规格层再拉起登录层，成功后继续原操作
  layerOpen.value = false
  if (!(await ensureLogin())) {
    layerOpen.value = true
    return
  }

  if (action === 'buy') {
    layerOpen.value = false
    buyOkOpen.value = true
    return
  }

  submitting.value = true
  try {
    await addCartItem(String(sku.skuId), qty.value)
    await cart.refresh()
    layerOpen.value = false
    cartOkOpen.value = true
  } catch {
    /* 加购失败（如库存不足）由请求层统一提示，保留规格层供用户改选 */
  } finally {
    submitting.value = false
  }
}

function onGoCart() {
  cartOkOpen.value = false
  void router.push('/cart')
}

function onGoCheckout() {
  const sku = chosenSku.value
  const detail = spu.value
  if (!sku || !detail) return
  buyOkOpen.value = false
  // spuId 供确认订单页复核商品快照与可售库存（PRD §8.7 确认订单）
  void router.push({
    path: '/checkout',
    query: {
      spuId: String(detail.id),
      skuId: String(sku.skuId),
      quantity: String(qty.value),
    },
  })
}

async function onFavorite() {
  if (!(await ensureLogin()) || !spu.value) return
  const id = String(spu.value.id)
  if (favorited.value) {
    await removeFavorite(id)
    favorited.value = false
    showToast('已取消收藏')
  } else {
    await addFavorite(id)
    favorited.value = true
    showToast('已收藏')
  }
}
</script>

<template>
  <div class="page product-page" :class="{ 'product-page--h5': isH5 }">
    <van-skeleton v-if="loading" title :row="8" />
    <template v-else-if="detail && spu">
      <div class="detail-grid">
        <div class="gallery-col">
          <div class="gallery">
            <img v-if="activePic" :src="resolveMediaUrl(activePic)" :alt="spu.name" />
            <div v-else class="gallery__empty">暂无主图</div>
          </div>
          <div v-if="pics.length > 1" class="thumbs">
            <button
              v-for="(pic, i) in pics"
              :key="pic"
              type="button"
              class="thumb"
              :class="{ active: i === activePicIndex }"
              @click="activePicIndex = i"
            >
              <img :src="resolveMediaUrl(pic)" alt="" />
            </button>
          </div>
        </div>

        <div class="buy-box">
          <h1 class="page-title">{{ spu.name }}</h1>
          <p v-if="spu.subtitle" class="page-desc">{{ spu.subtitle }}</p>

          <div class="price-block">
            <div class="price price-lg">{{ priceText }}</div>
            <div class="hint">已售 {{ soldCount }} · 共 {{ skus.length }} 个规格</div>
            <p class="badges">
              <span class="badge" :class="spuSoldOut ? 'badge-brick' : 'badge-forest'">
                {{ spuSoldOut ? '暂时缺货' : '有货' }}
              </span>
              <span class="badge badge-coral">满 99 免运</span>
            </p>
          </div>

          <div v-for="(dim, i) in dimensions" :key="dim.name" class="spec-block">
            <div class="spec-head">
              <span class="spec-cap" :class="{ 'is-err': specHighlight && !selected[dim.name] }">
                {{ dim.name }}<span class="req">*</span>
              </span>
              <button v-if="i === 0" type="button" class="link-hint" @click="openLayer()">
                选择规格 ›
              </button>
            </div>
            <div class="sku-row">
              <button
                v-for="option in dim.options"
                :key="option.value"
                type="button"
                class="chip"
                :class="{
                  active: selected[dim.name] === option.value,
                  'is-out': option.outOfStock,
                  'is-err': specHighlight && !selected[dim.name],
                }"
                @click="onChip(dim.name, option.value)"
              >
                {{ option.label }}
              </button>
            </div>
          </div>

          <div class="spec-block">
            <div class="spec-cap">数量</div>
            <div class="qty-line">
              <div class="qty">
                <button type="button" aria-label="减少数量" @click="openLayer()">−</button>
                <span>{{ qty }}</span>
                <button type="button" aria-label="增加数量" @click="openLayer()">+</button>
              </div>
              <span v-if="matchedSku" class="hint">{{ stockHint }}</span>
            </div>
            <p class="hint">{{ limitHint }}</p>
          </div>

          <div class="buy-actions">
            <button
              type="button"
              class="btn btn-secondary"
              :class="{ 'is-disabled': spuSoldOut }"
              :disabled="spuSoldOut"
              @click="onPageAction"
            >
              {{ spuSoldOut ? '暂时缺货' : '加入购物车' }}
            </button>
            <button
              type="button"
              class="btn btn-primary"
              :class="{ 'is-disabled': spuSoldOut }"
              :disabled="spuSoldOut"
              @click="onPageAction"
            >
              {{ spuSoldOut ? '暂时缺货' : '立即购买' }}
            </button>
          </div>

          <hr class="divider" />
          <p class="hint">正品保障 · 7 天无理由退货 · 商品金额满 99 元免运费</p>
          <button type="button" class="fav-link" :class="{ active: favorited }" @click="onFavorite">
            {{ favorited ? '已收藏' : '收藏' }}
          </button>
        </div>
      </div>

      <section v-if="detail.reviewsPreview?.length" class="reviews">
        <h2 class="section-title">商品评价</h2>
        <div v-for="r in detail.reviewsPreview" :key="String(r.id)" class="review">
          <div class="star-row">
            <svg
              v-for="n in 5"
              :key="n"
              class="ico star"
              :class="{ on: n <= r.rating }"
              viewBox="0 0 24 24"
            >
              <path d="M12 3l2.6 5.3 5.9.8-4.3 4.1 1 5.8-5.2-2.8-5.2 2.8 1-5.8L3.5 9.1l5.9-.8L12 3z" />
            </svg>
            <span class="hint">{{ r.rating }} 分</span>
          </div>
          <p>{{ r.content || '用户未填写文字' }}</p>
        </div>
      </section>

      <!-- PD-01 规格选择层：Web 居中对话框 / H5 底部半屏 -->
      <van-popup
        v-model:show="layerOpen"
        teleport="body"
        :position="isH5 ? 'bottom' : 'center'"
        :round="isH5"
        :safe-area-inset-bottom="isH5"
        :style="popupStyle"
      >
        <div class="pop-panel spec-layer" :class="isH5 ? 'is-sheet' : 'is-dialog'">
          <div v-if="isH5" class="sheet-grab" />
          <div class="pop-head">
            <span v-if="isWeb" class="pop-title">选择规格</span>
            <div v-else class="dlg-goods">
              <div class="goods-pic">
                <img v-if="activePic" :src="resolveMediaUrl(activePic)" alt="" />
              </div>
              <div class="goods-meta">
                <div class="t">{{ spu.name }}</div>
                <div class="s">已选：{{ selectedText }}</div>
                <div class="price">¥{{ formatPrice(displayPrice) }}</div>
              </div>
            </div>
            <button type="button" class="pop-x" aria-label="关闭" @click="layerOpen = false">
              <svg class="ico" viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18" /></svg>
            </button>
          </div>

          <div class="pop-body">
            <div v-if="isWeb" class="dlg-goods">
              <div class="goods-pic">
                <img v-if="activePic" :src="resolveMediaUrl(activePic)" alt="" />
              </div>
              <div class="goods-meta">
                <div class="t">{{ spu.name }}</div>
                <div class="s">已选：{{ selectedText }}</div>
                <div class="price">¥{{ formatPrice(displayPrice) }}</div>
              </div>
            </div>
            <hr v-if="isWeb" class="divider" />

            <div v-for="dim in dimensions" :key="dim.name" class="field">
              <span class="field-label" :class="{ 'is-err': specError && !selected[dim.name] }">
                {{ dim.name }}<span class="req">*</span>
              </span>
              <div class="sku-row">
                <button
                  v-for="option in dim.options"
                  :key="option.value"
                  type="button"
                  class="chip"
                  :class="{
                    active: selected[dim.name] === option.value,
                    'is-out': option.outOfStock,
                    'is-err': specError && !selected[dim.name],
                  }"
                  @click="onChip(dim.name, option.value)"
                >
                  {{ option.label }}
                </button>
              </div>
              <span v-if="specError && !selected[dim.name]" class="error-text">请选择规格</span>
            </div>

            <div class="field">
              <span class="field-label">数量</span>
              <div class="qty-line">
                <div class="qty">
                  <button type="button" aria-label="减少数量" @click="onQtyMinus">−</button>
                  <span>{{ qty }}</span>
                  <button type="button" aria-label="增加数量" @click="onQtyPlus">+</button>
                </div>
                <span v-if="matchedSku" class="hint">{{ stockHint }}</span>
              </div>
              <p class="hint">{{ limitHint }}</p>
            </div>

            <template v-if="selectedUnavailable">
              <p>
                该规格可售库存为 0，商品仍可浏览，但「加入购物车」「立即购买」按钮显示为「暂时缺货」且不可点击。
              </p>
              <div v-if="alternatives.length" class="alert alert-info">
                <svg class="ico" viewBox="0 0 24 24">
                  <circle cx="12" cy="12" r="9" /><path d="M12 11v5M12 7.6v.01" />
                </svg>
                可切换到 {{ alternatives.join(' / ') }} 规格下单。
              </div>
            </template>
          </div>

          <div class="pop-foot">
            <template v-if="selectedUnavailable">
              <button type="button" class="btn btn-secondary" @click="layerOpen = false">返回</button>
              <button type="button" class="btn btn-primary is-disabled" disabled>暂时缺货</button>
            </template>
            <template v-else>
              <button
                type="button"
                class="btn btn-secondary"
                :disabled="submitting"
                @click="onLayerAction('cart')"
              >
                加入购物车
              </button>
              <button
                type="button"
                class="btn btn-primary"
                :disabled="submitting"
                @click="onLayerAction('buy')"
              >
                立即购买
              </button>
            </template>
          </div>
        </div>
      </van-popup>

      <!-- PD-02 未选规格提示 / PD-03 库存不足提示 -->
      <van-popup
        v-model:show="noticeOpen"
        teleport="body"
        :position="isH5 ? 'bottom' : 'center'"
        :round="isH5"
        :safe-area-inset-bottom="isH5"
        :style="popupStyle"
      >
        <div v-if="notice" class="pop-panel notice-panel" :class="isH5 ? 'is-sheet' : 'is-dialog'">
          <div v-if="isH5" class="sheet-grab" />
          <div class="notice-center">
            <span class="notice-ico" :class="notice.tone">
              <svg class="ico" viewBox="0 0 24 24">
                <circle cx="12" cy="12" r="9" /><path d="M12 7.5v5.5M12 16.5v.01" />
              </svg>
            </span>
            <div class="notice-title">{{ notice.title }}</div>
            <div class="notice-desc">{{ notice.desc }}</div>
          </div>
          <div class="pop-foot">
            <button
              v-if="notice.gotoSpec"
              type="button"
              class="btn btn-secondary"
              @click="noticeOpen = false"
            >
              取消
            </button>
            <button type="button" class="btn btn-primary" @click="onNoticePrimary">
              {{ notice.primary }}
            </button>
          </div>
        </div>
      </van-popup>

      <!-- PD-05 加入购物车成功 -->
      <van-popup
        v-model:show="cartOkOpen"
        teleport="body"
        :position="isH5 ? 'bottom' : 'center'"
        :round="isH5"
        :safe-area-inset-bottom="isH5"
        :style="popupStyle"
      >
        <div class="pop-panel" :class="isH5 ? 'is-sheet' : 'is-dialog'">
          <div v-if="isH5" class="sheet-grab" />
          <div class="pop-head">
            <span class="notice-ico ok">
              <svg class="ico" viewBox="0 0 24 24"><path d="M4 12l5 5L20 6" /></svg>
            </span>
            <span class="pop-title">已加入购物车</span>
            <button type="button" class="pop-x" aria-label="关闭" @click="cartOkOpen = false">
              <svg class="ico" viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18" /></svg>
            </button>
          </div>
          <div class="pop-body">
            <div class="dlg-goods">
              <div class="goods-pic">
                <img v-if="activePic" :src="resolveMediaUrl(activePic)" alt="" />
              </div>
              <div class="goods-meta">
                <div class="t">{{ spu.name }}</div>
                <div class="s">{{ specsText(chosenSku) }} × {{ qty }}</div>
              </div>
              <div class="price">¥{{ formatPrice(displayPrice) }}</div>
            </div>
            <div class="kv-list">
              <div class="kv">
                <span class="k">购物车商品</span><span class="v num">{{ cart.count }} 件</span>
              </div>
              <div class="kv">
                <span class="k">商品金额</span>
                <span class="v num">¥{{ formatPrice(cartAmount) }}</span>
              </div>
              <div class="kv"><span class="k">运费</span><span class="v">免运费（满 99 元）</span></div>
            </div>
          </div>
          <div class="pop-foot">
            <button type="button" class="btn btn-secondary" @click="cartOkOpen = false">
              继续逛逛
            </button>
            <button type="button" class="btn btn-primary" @click="onGoCart">去购物车结算</button>
          </div>
        </div>
      </van-popup>

      <!-- PD-06 立即购买确认 -->
      <van-popup
        v-model:show="buyOkOpen"
        teleport="body"
        :position="isH5 ? 'bottom' : 'center'"
        :round="isH5"
        :safe-area-inset-bottom="isH5"
        :style="popupStyle"
      >
        <div class="pop-panel" :class="isH5 ? 'is-sheet' : 'is-dialog'">
          <div v-if="isH5" class="sheet-grab" />
          <div class="pop-head">
            <span class="pop-title">确认下单信息</span>
            <button type="button" class="pop-x" aria-label="关闭" @click="buyOkOpen = false">
              <svg class="ico" viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18" /></svg>
            </button>
          </div>
          <div class="pop-body">
            <div class="kv-list">
              <div class="kv"><span class="k">商品</span><span class="v">{{ spu.name }}</span></div>
              <div class="kv"><span class="k">规格</span><span class="v">{{ specsText(chosenSku) }}</span></div>
              <div class="kv"><span class="k">数量</span><span class="v num">{{ qty }}</span></div>
              <div class="kv">
                <span class="k">商品金额</span>
                <span class="v num">¥{{ formatPrice(goodsAmount) }}</span>
              </div>
              <div class="kv"><span class="k">运费</span><span class="v">免运费</span></div>
              <div class="kv">
                <span class="k">应付</span>
                <span class="v num is-coral">¥{{ formatPrice(goodsAmount) }}</span>
              </div>
            </div>
          </div>
          <div class="pop-foot">
            <button type="button" class="btn btn-secondary" @click="buyOkOpen = false">再想想</button>
            <button type="button" class="btn btn-primary" @click="onGoCheckout">去确认订单</button>
          </div>
        </div>
      </van-popup>
    </template>

    <div v-else class="empty-hint">
      <strong>商品不存在或已下架</strong>
      <RouterLink to="/">回首页</RouterLink>
    </div>
  </div>
</template>

<style scoped>
.product-page--h5 {
  padding-bottom: calc(84px + var(--safe-bottom));
}

.detail-grid {
  display: grid;
  gap: 20px;
}

@media (min-width: 768px) {
  .detail-grid {
    grid-template-columns: 1fr 1fr;
    gap: 40px;
  }
}

.gallery {
  border-radius: 16px;
  overflow: hidden;
  background: var(--sand);
  aspect-ratio: 1;
}

.gallery img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.gallery__empty {
  height: 100%;
  display: grid;
  place-items: center;
  color: var(--muted);
}

.thumbs {
  display: flex;
  gap: 10px;
  margin-top: 12px;
  flex-wrap: wrap;
}

.thumb {
  width: 64px;
  height: 64px;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
  background: var(--sand);
  cursor: pointer;
}

.thumb.active {
  border-color: var(--amber);
}

.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.buy-box {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.price-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.price-lg {
  font-size: 24px;
}

.badges {
  display: flex;
  gap: 8px;
  margin: 6px 0 0;
}

.badge {
  display: inline-flex;
  align-items: center;
  padding: 3px 9px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
}

.badge-forest {
  background: rgba(71, 112, 76, 0.16);
  color: #2f5230;
}

.badge-coral {
  background: rgba(255, 138, 91, 0.16);
  color: #b54a26;
}

.badge-brick {
  background: rgba(200, 69, 60, 0.12);
  color: #8e2a24;
}

.spec-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.spec-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.spec-cap,
.field-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--stone);
}

.spec-cap.is-err,
.field-label.is-err {
  color: var(--brick);
}

.req {
  color: var(--brick);
  margin-left: 2px;
}

.link-hint {
  border: 0;
  background: transparent;
  padding: 0;
  color: var(--coral);
  font-size: 12px;
  cursor: pointer;
}

.sku-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.chip {
  display: inline-flex;
  padding: 8px 14px;
  border-radius: 10px;
  border: 1px solid var(--border);
  background: var(--card);
  color: var(--ink);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}

.chip.active {
  border-color: var(--amber);
  background: rgba(254, 187, 74, 0.2);
}

.chip.is-out {
  color: var(--slate);
  text-decoration: line-through;
  background: var(--cream);
}

.chip.is-out.active {
  border-color: var(--slate);
  text-decoration: none;
}

.chip.is-err {
  border-color: var(--brick);
}

.qty-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.qty {
  display: inline-flex;
  align-items: center;
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
  background: var(--card);
}

.qty button {
  width: 32px;
  height: 32px;
  border: 0;
  background: var(--sand);
  font-size: 16px;
  color: var(--ink);
  cursor: pointer;
}

.qty span {
  min-width: 36px;
  text-align: center;
  font-family: var(--font-mono);
  font-size: 14px;
}

.hint {
  font-size: 12px;
  color: var(--slate);
  margin: 0;
}

.error-text {
  font-size: 12px;
  color: var(--brick);
}

.btn {
  font-weight: 600;
  font-size: 14px;
  border: 1px solid transparent;
  border-radius: 10px;
  padding: 10px 18px;
  cursor: pointer;
  line-height: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

.btn-primary {
  background: var(--amber);
  color: var(--ink);
}

.btn-secondary {
  background: var(--card);
  border-color: var(--border);
  color: var(--ink);
}

.btn.is-disabled,
.btn:disabled {
  background: var(--sand);
  color: var(--slate);
  border-color: var(--border);
  cursor: not-allowed;
}

.buy-actions {
  display: flex;
  gap: 12px;
}

.buy-actions .btn {
  flex: 1;
  min-height: 44px;
}

.divider {
  border: 0;
  border-top: 1px solid var(--border);
  margin: 0;
}

.fav-link {
  align-self: flex-start;
  border: 0;
  background: transparent;
  padding: 0;
  font-size: 13px;
  color: var(--muted);
  cursor: pointer;
}

.fav-link.active {
  color: var(--coral);
  font-weight: 600;
}

/* 单色描边图标（与原型 .ico 一致，不使用 emoji） */
.ico {
  width: 1em;
  height: 1em;
  flex-shrink: 0;
  display: inline-block;
  vertical-align: -0.12em;
  fill: none;
  stroke: currentColor;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.reviews {
  margin-top: 24px;
}

.section-title {
  font-size: 18px;
  font-weight: 700;
  margin: 0 0 12px;
}

.review {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 14px;
  padding: 12px 14px;
  margin-bottom: 10px;
}

.review p {
  margin: 6px 0 0;
  font-size: 13px;
}

.star-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.star {
  width: 14px;
  height: 14px;
  color: var(--border);
  fill: currentColor;
  stroke: currentColor;
  stroke-width: 1.2;
}

.star.on {
  color: var(--amber);
}

/* ===== 弹层：Web 居中对话框 / H5 底部半屏 ===== */
.pop-panel {
  background: var(--card);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.pop-panel.is-dialog {
  width: min(460px, 90vw);
  border-radius: 14px;
  box-shadow: var(--sh-2);
}

.pop-panel.is-sheet {
  width: 100%;
  border-radius: 16px 16px 0 0;
}

.notice-panel.is-dialog {
  width: min(380px, 90vw);
}

.sheet-grab {
  width: 36px;
  height: 4px;
  border-radius: 999px;
  background: var(--border);
  margin: 8px auto 0;
}

.pop-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px 10px;
}

.pop-title {
  flex: 1;
  font-size: 15px;
  font-weight: 700;
}

.pop-x {
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
  display: grid;
  place-items: center;
}

.pop-x .ico {
  width: 16px;
  height: 16px;
}

.pop-body {
  padding: 0 16px 16px;
  max-height: 60vh;
  overflow-y: auto;
  font-size: 13px;
}

.pop-body > p {
  margin: 0 0 10px;
}

.pop-foot {
  display: flex;
  gap: 10px;
  padding: 12px 16px calc(12px + var(--safe-bottom));
  border-top: 1px solid var(--border);
}

.pop-foot .btn {
  flex: 1;
  min-height: 42px;
}

.dlg-goods {
  display: flex;
  gap: 12px;
  align-items: center;
}

.goods-pic {
  width: 64px;
  height: 64px;
  flex-shrink: 0;
  border-radius: 10px;
  overflow: hidden;
  background: var(--sand);
}

.goods-pic img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.goods-meta {
  flex: 1;
  min-width: 0;
}

.goods-meta .t {
  font-weight: 600;
  font-size: 13px;
  line-height: 1.4;
}

.goods-meta .s {
  font-size: 12px;
  color: var(--slate);
  margin-top: 2px;
}

.goods-meta .price {
  font-size: 18px;
  margin-top: 4px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 16px;
}

.field:last-child {
  margin-bottom: 0;
}

.kv-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 16px;
}

.kv {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  font-size: 13px;
}

.kv .k {
  color: var(--stone);
  flex-shrink: 0;
}

.kv .v {
  color: var(--ink);
  text-align: right;
}

.kv .v.num {
  font-family: var(--font-mono);
  font-weight: 700;
}

.is-coral {
  color: var(--coral);
}

.alert {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  padding: 10px 12px;
  border-radius: 10px;
  font-size: 12px;
  line-height: 1.6;
  margin-top: 12px;
}

.alert .ico {
  width: 16px;
  height: 16px;
  margin-top: 1px;
}

.alert-info {
  background: rgba(254, 187, 74, 0.16);
  color: #8a5e10;
}

.notice-center {
  text-align: center;
  padding: 26px 20px 8px;
}

.notice-ico {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  margin: 0 auto 12px;
}

.notice-ico .ico {
  width: 22px;
  height: 22px;
}

.notice-ico.warn,
.notice-ico.info {
  background: rgba(254, 187, 74, 0.16);
  color: #9a6a12;
}

.notice-ico.danger {
  background: rgba(200, 69, 60, 0.12);
  color: var(--brick);
}

.notice-ico.ok {
  background: rgba(71, 112, 76, 0.16);
  color: var(--forest);
  flex-shrink: 0;
  margin: 0;
  width: 32px;
  height: 32px;
}

.notice-center .notice-title {
  font-size: 16px;
  font-weight: 700;
  margin-bottom: 6px;
}

.notice-center .notice-desc {
  font-size: 13px;
  color: var(--stone);
  line-height: 1.6;
}

@media (max-width: 767px) {
  .buy-actions {
    position: fixed;
    left: 0;
    right: 0;
    bottom: 0;
    z-index: 10;
    padding: 10px 14px calc(10px + var(--safe-bottom));
    background: var(--card);
    border-top: 1px solid var(--border);
  }
}
</style>