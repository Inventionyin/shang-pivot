<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { fetchAddresses, type AddressVO } from '@/api/customer'
import { fetchProductDetail } from '@/api/catalog'
import { submitOrder } from '@/api/order'
import { formatPrice, maskPhone, newIdempotentKey } from '@/utils/format'
import { resolveMediaUrl } from '@/utils/media'
import { useCartStore } from '@/stores/cart'
import { useClientType } from '@/composables/useClientType'
import StoreIcon from '@/components/StoreIcon.vue'

/**
 * 确认订单（PRD §8.7 / 原型 store/web|h5/checkout.html）
 * - 点「提交订单」先弹 CO-01 复核层（Web 居中 / H5 半屏）：地址 + 商品 + 金额明细
 * - 提交时库存不足按 CO-02 提示并停留本页（订单不生成）
 * - 幂等键按提交意图复用，重复点击只生成一个订单
 */
/** 运费规则：商品金额 ≥ 免运门槛免运费，否则默认运费（PRD §8.7，两项后台可配） */
const FREE_FREIGHT_THRESHOLD = 99
const DEFAULT_FREIGHT = 8
/** 后端 ErrorCode.STOCK_INSUFFICIENT */
const STOCK_INSUFFICIENT = 40901

const route = useRoute()
const router = useRouter()
const cart = useCartStore()
const { isH5 } = useClientType()

const addresses = ref<AddressVO[]>([])
const addressId = ref('')
const submitting = ref(false)

/** CO-01 提交订单确认 / CO-02 提交时库存不足（同一弹层位，Web 居中 / H5 半屏） */
const dialog = ref<'none' | 'confirm' | 'stock'>('none')
const dialogOpen = computed({
  get: () => dialog.value !== 'none',
  set: (v: boolean) => {
    if (!v) dialog.value = 'none'
  },
})
const popupStyle = { background: 'transparent' }

/** CO-02：库存不足的商品行；剩余可售数量解析不到时为 null */
type ShortageRow = {
  key: string
  title: string
  pic?: string | null
  quantity: number
  remaining: number | null
}
const shortages = ref<ShortageRow[]>([])

/** 立即购买：拉商品详情拿快照（名称 / 图 / 单价 / 可售库存） */
const buyNowSnapshot = ref<{ title: string; pic?: string | null; price: number; availableStock: number } | null>(
  null,
)

const buyNowSkuId = computed(() => String(route.query.skuId || ''))
const buyNowQty = computed(() => Number(route.query.quantity || 1))
const buyNowSpuId = computed(() => String(route.query.spuId || ''))

const cartItemIds = computed(() =>
  String(route.query.cartItemIds || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean),
)

const selectedItems = computed(() =>
  cart.items.filter((i) => cartItemIds.value.includes(String(i.cartItemId || i.id))),
)

const isBuyNow = computed(() => !!buyNowSkuId.value)

/** 复核/金额明细用的商品行（购物车快照或立即购买快照） */
type OrderLine = {
  key: string
  title: string
  pic?: string | null
  price: number
  quantity: number
}
const orderLines = computed<OrderLine[]>(() => {
  if (isBuyNow.value) {
    const snapshot = buyNowSnapshot.value
    return [
      {
        key: buyNowSkuId.value,
        title: snapshot?.title || `SKU ${buyNowSkuId.value}`,
        pic: snapshot?.pic || null,
        price: snapshot?.price || 0,
        quantity: buyNowQty.value,
      },
    ]
  }
  return selectedItems.value.map((i) => ({
    key: String(i.cartItemId || i.id),
    title: i.title,
    pic: i.pic,
    price: Number(i.price || 0),
    quantity: i.quantity,
  }))
})

const goodsCount = computed(() => orderLines.value.reduce((sum, l) => sum + l.quantity, 0))
const goodsAmount = computed(() => orderLines.value.reduce((sum, l) => sum + l.price * l.quantity, 0))
const freight = computed(() => (goodsAmount.value >= FREE_FREIGHT_THRESHOLD ? 0 : DEFAULT_FREIGHT))
const payAmount = computed(() => goodsAmount.value + freight.value)
/** 商品行摘要（原型 CO-01：单件给名称 × 数量，多件给「名称 等 N 件」） */
const goodsSummary = computed(() => {
  const lines = orderLines.value
  if (!lines.length) return '—'
  if (lines.length === 1) return `${lines[0].title} × ${lines[0].quantity}`
  return `${lines[0].title} 等 ${goodsCount.value} 件`
})

const selectedAddress = computed(
  () => addresses.value.find((a) => a.id === addressId.value) || null,
)
const fullAddress = computed(() => {
  const a = selectedAddress.value
  if (!a) return '—'
  return `${a.province || ''}${a.city || ''}${a.district || ''}${a.detail || ''}`
})

onMounted(async () => {
  if (!isBuyNow.value) await cart.refresh()
  if (isBuyNow.value && buyNowSpuId.value) await loadBuyNowSnapshot()
  try {
    const res = await fetchAddresses()
    addresses.value = (res.data || []).map((a) => ({ ...a, id: String(a.id) }))
    addressId.value = addresses.value.find((a) => a.isDefault)?.id || addresses.value[0]?.id || ''
  } catch {
    addresses.value = []
  }
})

/** 立即购买快照：详情接口已有 availableStock，无需后端新增能力 */
async function loadBuyNowSnapshot() {
  try {
    const res = await fetchProductDetail(buyNowSpuId.value)
    const sku = (res.data.skus || []).find((s) => String(s.skuId) === buyNowSkuId.value)
    if (sku) {
      buyNowSnapshot.value = {
        title: sku.title || res.data.spu?.name || '',
        pic: sku.pic || res.data.spu?.pics?.[0] || null,
        price: Number(sku.price || 0),
        availableStock: Number(sku.availableStock ?? 0),
      }
    }
  } catch {
    buyNowSnapshot.value = null
  }
}

/** 幂等键：同一提交意图（地址 + 商品）复用，成功后重置（PRD §8.7.3） */
let idempotentKey = ''
let idempotentSig = ''
function ensureIdempotentKey(): string {
  const sig = isBuyNow.value
    ? `${addressId.value}|buy:${buyNowSkuId.value}:${buyNowQty.value}`
    : `${addressId.value}|cart:${cartItemIds.value.join(',')}`
  if (!idempotentKey || idempotentSig !== sig) {
    idempotentKey = newIdempotentKey()
    idempotentSig = sig
  }
  return idempotentKey
}

function openConfirm() {
  if (!addressId.value) {
    showToast('请先添加收货地址')
    return
  }
  if (!isBuyNow.value && !cartItemIds.value.length) {
    showToast('没有待结算商品')
    return
  }
  dialog.value = 'confirm'
}

function bizCode(e: unknown): number {
  return Number((e as { code?: number } | null)?.code || 0)
}

async function confirmSubmit() {
  if (submitting.value) return
  submitting.value = true
  try {
    const res = await submitOrder({
      addressId: addressId.value,
      cartItemIds: isBuyNow.value ? undefined : cartItemIds.value,
      skuId: isBuyNow.value ? buyNowSkuId.value : undefined,
      quantity: isBuyNow.value ? buyNowQty.value : undefined,
      idempotentKey: ensureIdempotentKey(),
    })
    idempotentKey = ''
    idempotentSig = ''
    if (!isBuyNow.value) await cart.refresh()
    await router.replace(`/pay/${res.data.orderId}`)
  } catch (e) {
    // 库存不足：后端未指出具体商品，前端按现有接口（购物车失效原因 + 商品详情 availableStock）解析
    if (bizCode(e) === STOCK_INSUFFICIENT) await openStockShortage()
  } finally {
    submitting.value = false
  }
}

async function openStockShortage() {
  shortages.value = await collectShortages()
  dialog.value = 'stock'
}

async function collectShortages(): Promise<ShortageRow[]> {
  if (isBuyNow.value) {
    await loadBuyNowSnapshot()
    const snapshot = buyNowSnapshot.value
    return [
      {
        key: buyNowSkuId.value,
        title: snapshot?.title || `SKU ${buyNowSkuId.value}`,
        pic: snapshot?.pic || null,
        quantity: buyNowQty.value,
        remaining: snapshot ? snapshot.availableStock : null,
      },
    ]
  }

  await cart.refresh()
  const insufficient = cart.items.filter(
    (i) => !i.available && String(i.invalidReason || '').includes('库存'),
  )
  const rows: ShortageRow[] = insufficient.map((i) => ({
    key: String(i.cartItemId || i.id),
    title: i.title,
    pic: i.pic,
    quantity: i.quantity,
    remaining: null,
  }))
  // 购物车不下发可售数量，逐条查详情补齐「仅剩 N 件可售」
  await Promise.all(
    insufficient.slice(0, 6).map(async (item, index) => {
      const spuId = String(item.spuId || '')
      const row = rows[index]
      if (!spuId || !row) return
      try {
        const res = await fetchProductDetail(spuId)
        const sku = (res.data.skus || []).find((s) => String(s.skuId) === String(item.skuId))
        if (sku) row.remaining = Number(sku.availableStock ?? 0)
      } catch {
        row.remaining = null
      }
    }),
  )
  return rows
}

function shortageText(row: ShortageRow): string {
  if (row.remaining === null) return '库存不足，请调整数量后重试'
  if (row.remaining <= 0) return '当前无货'
  return `仅剩 ${row.remaining} 件可售`
}

function goCart() {
  dialog.value = 'none'
  void router.push('/cart')
}
</script>

<template>
  <div class="page checkout-page">
    <h1 class="page-title">确认订单</h1>

    <section class="block">
      <div class="block__head">
        <h2>收货地址</h2>
        <RouterLink to="/addresses">管理</RouterLink>
      </div>
      <van-radio-group v-if="addresses.length" v-model="addressId">
        <van-cell
          v-for="a in addresses"
          :key="a.id"
          clickable
          :title="`${a.name} ${a.phone}`"
          :label="`${a.province || ''}${a.city || ''}${a.district || ''}${a.detail}`"
          @click="addressId = a.id"
        >
          <template #right-icon>
            <van-radio :name="a.id" />
          </template>
        </van-cell>
      </van-radio-group>
      <div v-else class="empty-hint">
        <strong>还没有地址</strong>
        <RouterLink to="/addresses">去添加</RouterLink>
      </div>
    </section>

    <section class="block">
      <h2>商品清单（{{ goodsCount }} 件）</h2>
      <div v-for="line in orderLines" :key="line.key" class="line line--goods">
        <div class="thumb">
          <img v-if="line.pic" :src="resolveMediaUrl(line.pic)" alt="" />
        </div>
        <div class="line__body">
          <div class="line__title">{{ line.title }}</div>
          <div class="hint">× {{ line.quantity }}</div>
        </div>
        <span class="price">¥{{ formatPrice(line.price * line.quantity) }}</span>
      </div>
      <div v-if="!orderLines.length" class="empty-hint">请从购物车勾选商品再结算</div>
      <p v-if="isBuyNow && !buyNowSnapshot" class="hint">
        商品信息加载失败，最终金额以提交后生成的订单为准
      </p>
    </section>

    <!-- 金额明细（PRD §8.7：商品金额 / 运费 / 实付） -->
    <section class="block">
      <h2>订单摘要</h2>
      <div class="kv-list">
        <div class="kv">
          <span class="k">商品金额</span>
          <span class="v num">¥{{ formatPrice(goodsAmount) }}</span>
        </div>
        <div class="kv">
          <span class="k">运费</span>
          <span class="v">{{ freight === 0 ? '免运费' : `¥${formatPrice(freight)}` }}</span>
        </div>
      </div>
      <hr class="divider" />
      <div class="kv total">
        <span class="k">应付</span>
        <span class="v num">¥{{ formatPrice(payAmount) }}</span>
      </div>
      <p class="hint">商品金额满 {{ FREE_FREIGHT_THRESHOLD }} 元免运费，未满收 ¥{{ DEFAULT_FREIGHT }}</p>
    </section>

    <van-button type="primary" block :loading="submitting" @click="openConfirm">提交订单</van-button>

    <!-- CO-01 提交订单确认：Web 居中对话框 / H5 底部半屏 -->
    <van-popup
      v-model:show="dialogOpen"
      teleport="body"
      :position="isH5 ? 'bottom' : 'center'"
      :round="isH5"
      :safe-area-inset-bottom="isH5"
      :style="popupStyle"
    >
      <div v-if="dialog === 'confirm'" class="pop-panel" :class="isH5 ? 'is-sheet' : 'is-dialog'">
        <div v-if="isH5" class="sheet-grab" />
        <div class="pop-head">
          <span class="pop-title">确认提交订单</span>
          <button type="button" class="pop-x" aria-label="关闭" @click="dialog = 'none'">
            <StoreIcon name="close" :size="16" />
          </button>
        </div>
        <div class="pop-body">
          <div class="kv-list">
            <div class="kv">
              <span class="k">收货人</span>
              <span class="v">
                {{ selectedAddress ? `${selectedAddress.name} · ${maskPhone(selectedAddress.phone)}` : '—' }}
              </span>
            </div>
            <div class="kv">
              <span class="k">收货地址</span>
              <span class="v">{{ fullAddress }}</span>
            </div>
            <div class="kv">
              <span class="k">商品</span>
              <span class="v">{{ goodsSummary }}</span>
            </div>
            <div class="kv">
              <span class="k">商品金额</span>
              <span class="v num">¥{{ formatPrice(goodsAmount) }}</span>
            </div>
            <div class="kv">
              <span class="k">运费</span>
              <span class="v">{{ freight === 0 ? '免运费' : `¥${formatPrice(freight)}` }}</span>
            </div>
            <div class="kv">
              <span class="k">应付</span>
              <span class="v num coral">¥{{ formatPrice(payAmount) }}</span>
            </div>
          </div>
          <div class="pop-alert info">
            <StoreIcon name="alert" :size="16" />
            提交后生成待付款订单并锁定库存，15 分钟内未支付将自动关闭并释放库存。
          </div>
        </div>
        <div class="pop-foot">
          <button type="button" class="btn btn-secondary" @click="dialog = 'none'">取消</button>
          <button
            type="button"
            class="btn btn-primary"
            :disabled="submitting"
            @click="confirmSubmit"
          >
            确认提交
          </button>
        </div>
      </div>

      <!-- CO-02 提交时库存不足：订单不生成，停留本页 -->
      <div v-else-if="dialog === 'stock'" class="pop-panel" :class="isH5 ? 'is-sheet' : 'is-dialog is-sm'">
        <div v-if="isH5" class="sheet-grab" />
        <div class="pop-head">
          <span class="pop-head-ico danger"><StoreIcon name="alert" :size="18" /></span>
          <span class="pop-title">提交失败 · 库存不足</span>
          <button type="button" class="pop-x" aria-label="关闭" @click="dialog = 'none'">
            <StoreIcon name="close" :size="16" />
          </button>
        </div>
        <div class="pop-body">
          <p>提交时校验库存，以下商品可售库存不足，订单未生成：</p>
          <div v-for="row in shortages" :key="row.key" class="dlg-goods mt">
            <div class="pic">
              <img v-if="row.pic" :src="resolveMediaUrl(row.pic)" alt="" />
            </div>
            <div class="meta">
              <div class="t">{{ row.title }}</div>
              <div class="s">× {{ row.quantity }}</div>
              <div class="error-text mt">{{ shortageText(row) }}</div>
            </div>
          </div>
          <div class="pop-alert danger">请调整数量后重新提交，当前停留在确认订单页。</div>
        </div>
        <div class="pop-foot">
          <button type="button" class="btn btn-secondary" @click="dialog = 'none'">返回修改</button>
          <button v-if="!isBuyNow" type="button" class="btn btn-primary" @click="goCart">
            去购物车调整
          </button>
        </div>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.block {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 14px;
  padding: 14px;
  margin-bottom: 14px;
}

.block__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.block h2 {
  margin: 0 0 8px;
  font-size: 15px;
}

.line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px solid var(--border);
  font-size: 14px;
}

.line:last-child {
  border-bottom: 0;
}

.line--goods {
  align-items: flex-start;
}

.thumb {
  width: 56px;
  height: 56px;
  flex-shrink: 0;
  border-radius: var(--r-md);
  overflow: hidden;
  background: var(--sand);
}

.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.line__body {
  flex: 1;
  min-width: 0;
}

.line__title {
  font-weight: 600;
  font-size: 13px;
  line-height: 1.4;
}

.kv-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
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
  text-align: right;
}

.kv .v.num {
  font-family: var(--font-mono);
  font-weight: 700;
}

.kv .v.coral {
  color: var(--coral);
}

.kv.total .v.num {
  color: var(--coral);
  font-size: 16px;
}

.divider {
  border: 0;
  border-top: 1px solid var(--border);
  margin: 12px 0;
}

.mt {
  margin-top: 10px;
}
</style>