<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { formatPrice } from '@/utils/format'
import { resolveMediaUrl } from '@/utils/media'
import {
  clearInvalidCartItems,
  removeCartItem,
  updateCartItem,
  type CartItemVO,
} from '@/api/cart'
import { useCartStore } from '@/stores/cart'
import { useClientType } from '@/composables/useClientType'
import { openLoginLayer } from '@/composables/useLoginLayer'
import StoreIcon from '@/components/StoreIcon.vue'
import { getAccessToken } from '@/utils/request'

/** 购物车（PRD §8.6）：游客可浏览；失效条目置灰、不可勾选、可一键清理；结算前登录 */
const router = useRouter()
const cart = useCartStore()
const { isH5 } = useClientType()
const loading = ref(true)

const items = computed(() => cart.items)
const validItems = computed(() => items.value.filter((i) => i.available))
const invalidItems = computed(() => items.value.filter((i) => !i.available))
const selectedIds = computed(() =>
  items.value.filter((i) => i.selected && i.available).map((i) => i.cartItemId),
)
const validCount = computed(() => validItems.value.reduce((sum, i) => sum + i.quantity, 0))

const dialog = ref<'none' | 'delete' | 'clear'>('none')
const dialogOpen = computed({
  get: () => dialog.value !== 'none',
  set: (v: boolean) => {
    if (!v) dialog.value = 'none'
  },
})
const target = ref<CartItemVO | null>(null)
const submitting = ref(false)
const popupStyle = { background: 'transparent' }

onMounted(async () => {
  if (getAccessToken()) await cart.refresh()
  loading.value = false
})

/** 失效原因按 PRD 口径：已下架 / 库存为 0 */
function invalidReasonText(item: CartItemVO): string {
  const reason = item.invalidReason || ''
  if (reason.includes('下架')) return '已下架'
  if (reason.includes('库存')) return '库存为 0'
  return reason || '已失效'
}

async function toggle(item: CartItemVO) {
  if (!item.available) return
  await updateCartItem(item.cartItemId, { selected: !item.selected })
  await cart.refresh()
}

async function changeQty(item: CartItemVO, quantity: number) {
  await updateCartItem(item.cartItemId, { quantity })
  await cart.refresh()
}

function askRemove(item: CartItemVO) {
  target.value = item
  dialog.value = 'delete'
}

function askClearInvalid() {
  dialog.value = 'clear'
}

async function confirmRemove() {
  const item = target.value
  if (!item || submitting.value) return
  submitting.value = true
  try {
    await removeCartItem(item.cartItemId)
    await cart.refresh()
    dialog.value = 'none'
    target.value = null
  } finally {
    submitting.value = false
  }
}

async function confirmClearInvalid() {
  if (submitting.value) return
  submitting.value = true
  try {
    await clearInvalidCartItems()
    await cart.refresh()
    dialog.value = 'none'
    showToast('已清理失效商品')
  } finally {
    submitting.value = false
  }
}

async function checkout() {
  if (!selectedIds.value.length) {
    showToast('请先勾选商品')
    return
  }
  // PRD §8.1 / §8.6：结算需登录，未登录拉起登录层，成功后继续结算
  if (!(await openLoginLayer())) return
  router.push({
    path: '/checkout',
    query: { cartItemIds: selectedIds.value.join(',') },
  })
}
</script>

<template>
  <div class="page cart-page" :class="{ 'cart-page--h5': isH5 }">
    <h1 class="page-title">购物车</h1>
    <van-skeleton v-if="loading" title :row="5" />

    <template v-else>
      <div v-if="!items.length" class="empty-hint">
        <strong>购物车还是空的</strong>
        <p class="hint">去挑几件好物，满 99 元免运费</p>
        <RouterLink class="btn btn-primary go" to="/category">去逛逛</RouterLink>
      </div>

      <template v-else>
        <div v-if="validItems.length" class="valid-list">
          <div v-for="item in validItems" :key="item.cartItemId" class="cart-item">
            <van-checkbox
              :model-value="item.selected && item.available"
              @click="toggle(item)"
            />
            <img v-if="item.pic" :src="resolveMediaUrl(item.pic)" alt="" class="pic" />
            <div class="body">
              <div class="title">{{ item.title }}</div>
              <div class="price">¥{{ formatPrice(item.price) }}</div>
              <div class="row">
                <van-stepper
                  :model-value="item.quantity"
                  :min="1"
                  :disabled="!item.available"
                  @change="(v: number) => changeQty(item, v)"
                />
                <button type="button" class="link" @click="askRemove(item)">删除</button>
              </div>
            </div>
          </div>
        </div>

        <!-- 失效商品：已下架 / 库存为 0，置灰、不可勾选、可一键清理（PRD §8.6） -->
        <section v-if="invalidItems.length" class="invalid-block">
          <div class="invalid-head">
            <span>失效商品（{{ invalidItems.length }}）</span>
            <button type="button" class="link coral" @click="askClearInvalid">一键清理</button>
          </div>
          <div v-for="item in invalidItems" :key="item.cartItemId" class="cart-item is-invalid">
            <van-checkbox :model-value="false" disabled />
            <img v-if="item.pic" :src="resolveMediaUrl(item.pic)" alt="" class="pic" />
            <div class="body">
              <div class="title">{{ item.title }}</div>
              <div class="tags">
                <span class="badge-invalid">{{ invalidReasonText(item) }}</span>
              </div>
              <div class="hint">失效商品不参与结算</div>
              <div class="row">
                <span class="old-price">¥{{ formatPrice(item.price) }}</span>
                <span class="hint">× {{ item.quantity }}</span>
                <button type="button" class="link" @click="askRemove(item)">删除</button>
              </div>
            </div>
          </div>
        </section>

        <div class="footer">
          <button
            v-if="invalidItems.length"
            type="button"
            class="link"
            @click="askClearInvalid"
          >
            清理失效
          </button>
          <div class="sum">
            已选 {{ selectedIds.length }} 件 · 合计
            <span class="price">¥{{ formatPrice(cart.selectedAmount) }}</span>
          </div>
          <button type="button" class="btn btn-primary" @click="checkout">去结算</button>
        </div>
        <p class="hint foot-hint">结算仅携带勾选的有效商品；商品金额满 99 元免运费，未满收 ¥8</p>
      </template>
    </template>

    <!-- P-1 删除商品确认 -->
    <van-popup
      v-model:show="dialogOpen"
      teleport="body"
      :position="isH5 ? 'bottom' : 'center'"
      :round="isH5"
      :safe-area-inset-bottom="isH5"
      :style="popupStyle"
    >
      <div v-if="dialog === 'delete' && target" class="pop-panel" :class="[isH5 ? 'is-sheet' : 'is-dialog is-sm']">
        <div v-if="isH5" class="sheet-grab" />
        <div class="pop-head">
          <span class="pop-head-ico danger"><StoreIcon name="trash" :size="18" /></span>
          <span class="pop-title">删除商品</span>
          <button type="button" class="pop-x" aria-label="关闭" @click="dialog = 'none'">
            <StoreIcon name="close" :size="16" />
          </button>
        </div>
        <div class="pop-body">
          <div class="dlg-goods">
            <div class="pic">
              <img v-if="target.pic" :src="resolveMediaUrl(target.pic)" alt="" />
            </div>
            <div class="meta">
              <div class="t">{{ target.title }}</div>
              <div class="s">× {{ target.quantity }}</div>
            </div>
          </div>
          <div class="pop-alert warn">
            <StoreIcon name="alert" :size="16" />
            删除后该商品不再保留在购物车，需要时可在商品详情页重新加入。
          </div>
        </div>
        <div class="pop-foot">
          <button type="button" class="btn btn-secondary" @click="dialog = 'none'">取消</button>
          <button type="button" class="btn btn-danger" :disabled="submitting" @click="confirmRemove">
            删除
          </button>
        </div>
      </div>

      <!-- P-2 一键清理失效商品确认 -->
      <div v-else-if="dialog === 'clear'" class="pop-panel" :class="[isH5 ? 'is-sheet' : 'is-dialog is-sm']">
        <div v-if="isH5" class="sheet-grab" />
        <div class="pop-head">
          <span class="pop-head-ico warn"><StoreIcon name="alert" :size="18" /></span>
          <span class="pop-title">清理失效商品</span>
          <button type="button" class="pop-x" aria-label="关闭" @click="dialog = 'none'">
            <StoreIcon name="close" :size="16" />
          </button>
        </div>
        <div class="pop-body">
          <p class="muted">以下商品已下架或库存为 0，清理后不再显示在购物车。</p>
          <div v-for="item in invalidItems" :key="item.cartItemId" class="dlg-goods mt">
            <div class="pic">
              <img v-if="item.pic" :src="resolveMediaUrl(item.pic)" alt="" />
            </div>
            <div class="meta">
              <div class="t">{{ item.title }}</div>
              <div class="s">{{ invalidReasonText(item) }} × {{ item.quantity }}</div>
            </div>
          </div>
          <div class="kv-list">
            <div class="kv">
              <span class="k">失效商品</span>
              <span class="v num">{{ invalidItems.length }} 件（已下架 / 库存为 0）</span>
            </div>
            <div class="kv">
              <span class="k">有效商品</span>
              <span class="v num">{{ validCount }} 件（不受影响）</span>
            </div>
          </div>
        </div>
        <div class="pop-foot">
          <button type="button" class="btn btn-secondary" @click="dialog = 'none'">取消</button>
          <button
            type="button"
            class="btn btn-primary"
            :disabled="submitting"
            @click="confirmClearInvalid"
          >
            确认清理
          </button>
        </div>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.cart-item {
  display: grid;
  grid-template-columns: auto 72px 1fr;
  gap: 10px;
  align-items: start;
  padding: 12px;
  margin-bottom: 10px;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
}

/* 失效条目：置灰（PRD §8.6） */
.cart-item.is-invalid {
  background: var(--cream);
  border-color: var(--border);
}

.cart-item.is-invalid .pic {
  filter: grayscale(0.75);
  opacity: 0.7;
}

.cart-item.is-invalid .title {
  color: var(--slate);
}

.pic {
  width: 72px;
  height: 72px;
  object-fit: cover;
  border-radius: 10px;
  background: var(--sand);
}

.title {
  font-size: 14px;
  margin-bottom: 6px;
}

.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
}

.tags {
  margin-bottom: 4px;
}

.badge-invalid {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--sand);
  color: var(--stone);
  font-size: 11px;
  font-weight: 600;
}

.old-price {
  font-family: var(--font-mono);
  color: var(--slate);
  text-decoration: line-through;
  font-size: 13px;
}

.invalid-block {
  margin: 16px 0;
}

.invalid-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  background: var(--sand);
  border: 1px solid var(--border);
  border-radius: var(--r-md) var(--r-md) 0 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--stone);
}

.invalid-head .coral {
  color: var(--coral);
}

.invalid-block .cart-item {
  margin-bottom: 0;
  border-radius: 0;
  border-top: 0;
}

.invalid-block .cart-item:last-child {
  border-radius: 0 0 var(--r-md) var(--r-md);
}

.link {
  border: 0;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
  font-size: 12px;
  padding: 0;
}

.link:hover {
  color: var(--coral);
}

.footer {
  position: sticky;
  bottom: calc(var(--tabbar-h) + var(--safe-bottom) + 8px);
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
  padding: 12px 14px;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  box-shadow: var(--sh-1);
}

.sum {
  margin-left: auto;
  font-size: 13px;
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.sum .price {
  font-size: 18px;
  font-weight: 700;
}

.foot-hint {
  text-align: center;
  margin-top: 10px;
}

.empty-hint .go {
  margin-top: 12px;
}

.mt {
  margin-top: 12px;
}

/* H5：卡片列表 + 底部安全区（PRD §8.13） */
.cart-page--h5 {
  padding-left: 12px;
  padding-right: 12px;
}

@media (min-width: 768px) {
  .footer {
    bottom: 16px;
  }
}
</style>