<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showToast, type UploaderFileListItem } from 'vant'
import {
  cancelOrder,
  confirmOrder,
  createReview,
  fetchOrderDetail,
  fetchOrderLogistics,
} from '@/api/order'
import { uploadFile } from '@/api/customer'
import { formatPrice } from '@/utils/format'
import { resolveMediaUrl } from '@/utils/media'
import { useClientType } from '@/composables/useClientType'

const route = useRoute()
const router = useRouter()
const { isH5 } = useClientType()
const id = String(route.params.id || '')
const order = ref<Record<string, unknown> | null>(null)
const items = ref<Array<Record<string, unknown>>>([])
const shipments = ref<Array<Record<string, unknown>>>([])
const loading = ref(true)

const status = computed(() => String(order.value?.status || ''))

/** RD-06 评价表单：1~5 星 / ≤500 字 / ≤3 图，每个订单项一次，提交后不可修改 */
const reviewOpen = ref(false)
const reviewItem = ref<Record<string, unknown> | null>(null)
const review = reactive({ rating: 0, content: '', picKeys: [] as string[] })
const reviewFiles = ref<UploaderFileListItem[]>([])
const ratingError = ref(false)
const submitting = ref(false)
const reviewedItems = ref<string[]>([])

async function load() {
  loading.value = true
  try {
    const res = await fetchOrderDetail(id)
    const data = res.data as {
      order?: Record<string, unknown>
      items?: Array<Record<string, unknown>>
      shipments?: Array<Record<string, unknown>>
    }
    order.value = data.order || null
    items.value = data.items || []
    shipments.value = data.shipments || []
  } catch {
    order.value = null
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function onCancel() {
  await showConfirmDialog({ title: '取消订单', message: '确认取消该待付款订单？' })
  await cancelOrder(id)
  showToast('已取消')
  await load()
}

async function onConfirm() {
  await showConfirmDialog({ title: '确认收货', message: '确认已收到商品？' })
  await confirmOrder(id)
  showToast('已确认收货')
  await load()
}

function onPay() {
  router.push(`/pay/${id}`)
}

function onAfterSale() {
  router.push({ path: '/after-sales/apply', query: { orderId: id } })
}

function specsText(item: Record<string, unknown>): string {
  const specs = item.specs as Record<string, unknown> | undefined
  const values = specs ? Object.values(specs).filter((v) => v !== null && v !== undefined && v !== '') : []
  return values.length ? values.map(String).join(' · ') : '默认规格'
}

function isReviewed(itemId: string): boolean {
  return reviewedItems.value.includes(itemId)
}

function onReview(item: Record<string, unknown>) {
  reviewItem.value = item
  review.rating = 0
  review.content = ''
  review.picKeys = []
  reviewFiles.value = []
  ratingError.value = false
  reviewOpen.value = true
}

function selectRating(score: number) {
  review.rating = score
  ratingError.value = false
}

async function onUpload(file: File) {
  try {
    const res = await uploadFile(file, 'REVIEW')
    review.picKeys.push(res.data.objectKey)
  } catch {
    /* 上传失败由请求层统一提示 */
  }
}

function afterRead(file: UploaderFileListItem | UploaderFileListItem[]) {
  const list = Array.isArray(file) ? file : [file]
  for (const item of list) {
    if (item.file) void onUpload(item.file)
  }
}

function onDeletePic(_file: UploaderFileListItem, detail: { index: number }) {
  review.picKeys.splice(detail.index, 1)
}

async function onSubmitReview() {
  const item = reviewItem.value
  if (!item || submitting.value) return
  if (!review.rating) {
    ratingError.value = true
    return
  }
  submitting.value = true
  try {
    await createReview({
      orderItemId: String(item.id),
      rating: review.rating,
      content: review.content.trim() || undefined,
      picKeys: review.picKeys.length ? review.picKeys : undefined,
    })
    reviewedItems.value = [...reviewedItems.value, String(item.id)]
    reviewOpen.value = false
    showToast('评价成功')
  } catch {
    /* 已评价等异常由请求层统一提示 */
  } finally {
    submitting.value = false
  }
}

async function onLogistics() {
  const res = await fetchOrderLogistics(id)
  const list = res.data || []
  if (!list.length) {
    showToast('暂无物流信息')
    return
  }
  const first = list[0] as Record<string, unknown>
  showToast(`${first.carrier || ''} ${first.trackingNo || ''}`)
}
</script>

<template>
  <div class="page">
    <h1 class="page-title">订单详情</h1>
    <van-skeleton v-if="loading" title :row="6" />
    <template v-else-if="order">
      <div class="panel">
        <div>状态 {{ status }}</div>
        <div>订单号 {{ order.orderNo || id }}</div>
        <div class="price">实付 ¥{{ formatPrice(order.payAmount as number) }}</div>
      </div>

      <div v-for="it in items" :key="String(it.id)" class="item">
        <img v-if="it.pic" :src="resolveMediaUrl(String(it.pic))" alt="" />
        <div>
          <div>{{ it.title }}</div>
          <div class="muted">× {{ it.quantity }} · ¥{{ formatPrice(it.subtotal as number) }}</div>
          <template v-if="status === 'COMPLETED'">
            <van-button
              v-if="!isReviewed(String(it.id))"
              size="mini"
              plain
              style="margin-top: 6px"
              @click="onReview(it)"
            >
              评价
            </van-button>
            <span v-else class="muted">已评价 · 提交后不可修改</span>
          </template>
        </div>
      </div>

      <div v-if="shipments.length" class="panel">
        <div v-for="s in shipments" :key="String(s.id)">
          物流 {{ s.carrier }} {{ s.trackingNo }} · {{ s.status }}
        </div>
      </div>

      <div class="actions">
        <van-button v-if="status === 'PENDING_PAY'" type="primary" block @click="onPay">
          去支付
        </van-button>
        <van-button v-if="status === 'PENDING_PAY'" plain block @click="onCancel">
          取消订单
        </van-button>
        <van-button v-if="status === 'PENDING_RECEIVE'" type="primary" block @click="onConfirm">
          确认收货
        </van-button>
        <van-button
          v-if="status === 'PENDING_RECEIVE' || status === 'COMPLETED'"
          plain
          block
          @click="onAfterSale"
        >
          申请售后
        </van-button>
        <van-button
          v-if="status === 'PENDING_RECEIVE' || status === 'COMPLETED'"
          plain
          block
          @click="onLogistics"
        >
          查看物流
        </van-button>
      </div>

      <!-- RD-06 评价表单：Web 居中对话框 / H5 底部半屏 -->
      <van-popup
        v-model:show="reviewOpen"
        teleport="body"
        :position="isH5 ? 'bottom' : 'center'"
        :round="isH5"
        :safe-area-inset-bottom="isH5"
        :style="{ background: 'transparent' }"
      >
        <div v-if="reviewItem" class="pop-panel" :class="isH5 ? 'is-sheet' : 'is-dialog'">
          <div v-if="isH5" class="sheet-grab" />
          <div class="pop-head">
            <span class="pop-title">发表评价</span>
            <button type="button" class="pop-x" aria-label="关闭" @click="reviewOpen = false">
              <svg class="ico" viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18" /></svg>
            </button>
          </div>

          <div class="pop-body">
            <div class="dlg-goods">
              <div class="goods-pic">
                <img v-if="reviewItem.pic" :src="resolveMediaUrl(String(reviewItem.pic))" alt="" />
              </div>
              <div class="goods-meta">
                <div class="t">{{ reviewItem.title }}</div>
                <div class="s">{{ specsText(reviewItem) }} × {{ reviewItem.quantity }}</div>
              </div>
            </div>
            <hr class="divider" />

            <div class="field">
              <span class="field-label" :class="{ 'is-err': ratingError }">
                商品评分<span class="req">*</span>
              </span>
              <div class="star-row">
                <button
                  v-for="n in 5"
                  :key="n"
                  type="button"
                  class="star"
                  :class="{ on: n <= review.rating }"
                  :aria-label="`${n} 分`"
                  @click="selectRating(n)"
                >
                  <svg viewBox="0 0 24 24">
                    <path
                      d="M12 3l2.6 5.3 5.9.8-4.3 4.1 1 5.8-5.2-2.8-5.2 2.8 1-5.8L3.5 9.1l5.9-.8L12 3z"
                    />
                  </svg>
                </button>
                <span class="score">{{ review.rating ? `${review.rating} 分` : '请打分' }}</span>
              </div>
              <span v-if="ratingError" class="error-text">请选择商品评分</span>
            </div>

            <div class="field">
              <span class="field-label">评价内容（选填）</span>
              <textarea
                v-model="review.content"
                class="input"
                maxlength="500"
                placeholder="说说使用感受，最多 500 字"
              />
              <span class="hint">{{ review.content.length }} / 500</span>
            </div>

            <div class="field">
              <span class="field-label">晒图（选填，最多 3 张）</span>
              <van-uploader
                v-model="reviewFiles"
                :max-count="3"
                accept="image/jpeg,image/png"
                :after-read="afterRead"
                @delete="onDeletePic"
              >
                <div class="up-box">
                  <svg class="ico" viewBox="0 0 24 24"><path d="M12 6v12M6 12h12" /></svg>
                  <span>上传图片</span>
                </div>
              </van-uploader>
              <span class="hint">jpeg / png，单张 ≤10MB · 已上传 {{ review.picKeys.length }} / 3</span>
            </div>

            <div class="alert alert-info">
              <svg class="ico" viewBox="0 0 24 24">
                <circle cx="12" cy="12" r="9" /><path d="M12 11v5M12 7.6v.01" />
              </svg>
              仅已完成订单可评价，每个订单项一次，提交后不可修改；评价默认直接展示。
            </div>
          </div>

          <div class="pop-foot">
            <button type="button" class="btn btn-secondary" @click="reviewOpen = false">取消</button>
            <button
              type="button"
              class="btn btn-primary"
              :disabled="submitting"
              @click="onSubmitReview"
            >
              提交评价
            </button>
          </div>
        </div>
      </van-popup>
    </template>
    <div v-else class="empty-hint">订单不存在</div>
  </div>
</template>

<style scoped>
.panel {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 14px;
  padding: 16px;
  display: grid;
  gap: 8px;
  margin-bottom: 16px;
}

.item {
  display: grid;
  grid-template-columns: 64px 1fr;
  gap: 10px;
  margin-bottom: 10px;
  padding: 10px;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 12px;
}

.item img {
  width: 64px;
  height: 64px;
  object-fit: cover;
  border-radius: 8px;
}

.muted {
  color: var(--muted);
  font-size: 12px;
  margin-top: 4px;
}

.actions {
  display: grid;
  gap: 10px;
  margin-top: 16px;
}

/* ===== RD-06 评价层：Web 居中对话框 / H5 底部半屏 ===== */
.pop-panel {
  background: var(--card);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.pop-panel.is-dialog {
  width: min(640px, 92vw);
  border-radius: 14px;
  box-shadow: var(--sh-2);
}

.pop-panel.is-sheet {
  width: 100%;
  border-radius: 16px 16px 0 0;
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
  max-height: 62vh;
  overflow-y: auto;
  font-size: 13px;
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

.btn:disabled {
  background: var(--sand);
  color: var(--slate);
  border-color: var(--border);
  cursor: not-allowed;
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

.divider {
  border: 0;
  border-top: 1px solid var(--border);
  margin: 12px 0;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 16px;
}

.field-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--stone);
}

.field-label.is-err {
  color: var(--brick);
}

.req {
  color: var(--brick);
  margin-left: 2px;
}

.hint {
  font-size: 12px;
  color: var(--slate);
}

.error-text {
  font-size: 12px;
  color: var(--brick);
}

.star-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.star {
  width: 26px;
  height: 26px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--border);
  cursor: pointer;
}

.star svg {
  width: 100%;
  height: 100%;
  fill: currentColor;
  stroke: currentColor;
  stroke-width: 1.2;
}

.star.on {
  color: var(--amber);
}

.score {
  margin-left: 8px;
  font-size: 12px;
  color: var(--stone);
}

.input {
  width: 100%;
  font-family: var(--font-ui);
  font-size: 14px;
  padding: 10px 14px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--card);
  color: var(--ink);
  resize: vertical;
  min-height: 76px;
  line-height: 1.6;
}

.input:focus {
  outline: none;
  border-color: var(--amber);
  box-shadow: 0 0 0 3px rgba(254, 187, 74, 0.35);
}

.up-box {
  width: 76px;
  height: 76px;
  border-radius: 10px;
  border: 1px dashed var(--border);
  background: var(--cream);
  display: grid;
  place-items: center;
  gap: 2px;
  text-align: center;
  font-size: 11px;
  color: var(--slate);
  cursor: pointer;
  padding: 6px;
}

.up-box .ico {
  width: 20px;
  height: 20px;
}

.alert {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  padding: 10px 12px;
  border-radius: 10px;
  font-size: 12px;
  line-height: 1.6;
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
</style>