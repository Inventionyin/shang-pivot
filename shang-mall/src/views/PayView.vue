<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchOrderDetail, sandboxPay } from '@/api/order'
import { formatPrice, maskPhone } from '@/utils/format'

/** CO-05 支付中 / CO-06 支付成功 / CO-07 支付失败（可重试）/ CO-08 支付结果页 */
type PayStage = 'loading' | 'ok' | 'fail' | 'result'

/** 待付款订单支付时限（后端 order.pay-timeout-minutes 默认 15 分钟） */
const PAY_TIMEOUT_MINUTES = 15

const route = useRoute()
const router = useRouter()

const orderId = String(route.params.orderId || '')
const order = ref<Record<string, unknown> | null>(null)
const loading = ref(true)
const stage = ref<PayStage>('loading')
const errorMsg = ref('')

const orderNo = computed(() => String(order.value?.orderNo || orderId))
const payAmount = computed(() => Number(order.value?.payAmount ?? 0))
const receiver = computed(() => {
  const snapshot = order.value?.addressSnapshot as Record<string, unknown> | undefined
  const name = String(snapshot?.name || order.value?.customerName || '')
  if (!name) return ''
  const phone = String(snapshot?.phone || '')
  return phone ? `${name} · ${maskPhone(phone)}` : name
})

const now = ref(Date.now())
let ticker: number | undefined
const remainText = computed(() => {
  const createdAt = Date.parse(String(order.value?.createdAt || ''))
  if (Number.isNaN(createdAt)) return '15:00'
  const left = createdAt + PAY_TIMEOUT_MINUTES * 60_000 - now.value
  if (left <= 0) return '00:00'
  const seconds = Math.floor(left / 1000)
  const mm = String(Math.floor(seconds / 60)).padStart(2, '0')
  const ss = String(seconds % 60).padStart(2, '0')
  return `${mm}:${ss}`
})

function pickMsg(err: unknown): string {
  if (err && typeof err === 'object' && 'msg' in err) {
    const msg = (err as { msg?: unknown }).msg
    if (typeof msg === 'string' && msg) return msg
  }
  return ''
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => {
    window.setTimeout(resolve, ms)
  })
}

/** 支付中 → 成功 / 失败：结果由沙箱支付接口返回或异常决定 */
async function startPay() {
  stage.value = 'loading'
  errorMsg.value = ''
  try {
    const [res] = await Promise.all([sandboxPay(orderId), sleep(900)])
    if (!res.data || String(res.data.status || '') !== 'PENDING_SHIP') {
      errorMsg.value = ''
      stage.value = 'fail'
      return
    }
    stage.value = 'ok'
  } catch (err) {
    errorMsg.value = pickMsg(err)
    stage.value = 'fail'
  }
}

onMounted(async () => {
  try {
    const res = await fetchOrderDetail(orderId)
    order.value = (res.data as { order?: Record<string, unknown> }).order || null
  } catch {
    order.value = null
  } finally {
    loading.value = false
  }

  const status = String(order.value?.status || '')
  if (!order.value) {
    stage.value = 'fail'
  } else if (status === 'PENDING_PAY') {
    void startPay()
  } else if (status === 'PENDING_SHIP' || status === 'PENDING_RECEIVE' || status === 'COMPLETED') {
    stage.value = 'ok'
  } else {
    stage.value = 'fail'
    errorMsg.value = '订单已关闭，无法继续支付'
  }

  ticker = window.setInterval(() => {
    now.value = Date.now()
  }, 1000)
})

onUnmounted(() => {
  if (ticker !== undefined) window.clearInterval(ticker)
})

function onCancelPay() {
  void router.replace(`/orders/${orderId}`)
}

function onHome() {
  void router.push('/')
}

function onOrder() {
  void router.push(`/orders/${orderId}`)
}
</script>

<template>
  <div class="page">
    <van-skeleton v-if="loading" title :row="5" />
    <template v-else>
      <div class="pay-card" :class="{ 'is-wide': stage === 'result' }">
        <div class="pay-head">
          <span class="pay-title">{{ stage === 'result' ? '支付结果' : '收银台' }}</span>
          <button
            v-if="stage !== 'result'"
            type="button"
            class="pay-x"
            aria-label="关闭"
            @click="onCancelPay"
          >
            <svg class="ico" viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18" /></svg>
          </button>
        </div>

        <!-- CO-05 支付中 -->
        <div v-if="stage === 'loading'" class="pay-body">
          <div class="pay-spin" />
          <div class="pay-strong">支付中…</div>
          <div class="pay-amount">¥{{ formatPrice(payAmount) }}</div>
          <p class="hint">沙箱支付（模拟），约 1 秒返回结果</p>
        </div>

        <!-- CO-06 支付成功 -->
        <div v-else-if="stage === 'ok'" class="pay-body">
          <div class="pay-state ok">
            <svg class="ico" viewBox="0 0 24 24"><path d="M4 12l5 5L20 6" /></svg>
          </div>
          <div class="pay-strong">支付成功</div>
          <div class="muted">订单号 {{ orderNo }} · 待发货</div>
          <p class="hint">商家将尽快为你发货，发货后可查看物流轨迹</p>
        </div>

        <!-- CO-07 支付失败（可重试） -->
        <div v-else-if="stage === 'fail'" class="pay-body">
          <div class="pay-state err">
            <svg class="ico" viewBox="0 0 24 24">
              <circle cx="12" cy="12" r="9" /><path d="M12 7.5v5.5M12 16.5v.01" />
            </svg>
          </div>
          <div class="pay-strong">支付失败</div>
          <div class="muted">{{ errorMsg || '订单仍为待付款，可重新支付' }}</div>
          <p class="hint">剩余支付时间 {{ remainText }}，超时订单将自动关闭并释放库存</p>
        </div>

        <!-- CO-08 支付结果页 -->
        <div v-else class="pay-body">
          <div class="pay-state ok">
            <svg class="ico" viewBox="0 0 24 24"><path d="M4 12l5 5L20 6" /></svg>
          </div>
          <div class="pay-result-title">支付成功</div>
          <div class="hint center">订单已进入待发货，商家将尽快发货</div>
          <div class="kv-list">
            <div class="kv"><span class="k">订单号</span><span class="v num">{{ orderNo }}</span></div>
            <div class="kv">
              <span class="k">支付金额</span>
              <span class="v num coral">¥{{ formatPrice(payAmount) }}</span>
            </div>
            <div class="kv"><span class="k">支付方式</span><span class="v">沙箱支付（模拟）</span></div>
            <div class="kv"><span class="k">订单状态</span><span class="v">待发货</span></div>
            <div v-if="receiver" class="kv">
              <span class="k">收货人</span><span class="v">{{ receiver }}</span>
            </div>
          </div>
        </div>

        <div class="pay-foot">
          <template v-if="stage === 'loading'">
            <button type="button" class="btn btn-secondary" @click="onCancelPay">取消支付</button>
          </template>
          <template v-else-if="stage === 'ok'">
            <button type="button" class="btn btn-secondary" @click="onHome">返回首页</button>
            <button type="button" class="btn btn-primary" @click="stage = 'result'">
              查看支付结果
            </button>
          </template>
          <template v-else-if="stage === 'fail'">
            <button type="button" class="btn btn-secondary" @click="onCancelPay">稍后支付</button>
            <button type="button" class="btn btn-primary" @click="startPay">重试支付</button>
          </template>
          <template v-else>
            <button type="button" class="btn btn-secondary" @click="onHome">返回首页</button>
            <button type="button" class="btn btn-primary" @click="onOrder">查看订单</button>
          </template>
        </div>
      </div>

      <p class="pay-tip">
        本版本为模拟支付，无真实资金流；支付失败订单保持待付款，可重试。
      </p>
    </template>
  </div>
</template>

<style scoped>
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

.pay-card {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 14px;
  box-shadow: var(--sh-1);
  overflow: hidden;
  margin: 0 auto;
  width: 100%;
  max-width: 400px;
  text-align: center;
}

.pay-card.is-wide {
  max-width: 460px;
  text-align: left;
}

.pay-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px 6px;
}

.pay-title {
  flex: 1;
  font-size: 15px;
  font-weight: 700;
  text-align: left;
}

.pay-x {
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

.pay-x .ico {
  width: 16px;
  height: 16px;
}

.pay-body {
  padding: 8px 20px 18px;
  font-size: 13px;
}

.pay-strong {
  font-weight: 700;
  font-size: 16px;
  margin-bottom: 6px;
}

.pay-amount {
  font-family: var(--font-mono);
  font-size: 32px;
  font-weight: 700;
  color: var(--coral);
  margin-bottom: 6px;
}

.pay-spin {
  width: 40px;
  height: 40px;
  margin: 4px auto 14px;
  border-radius: 50%;
  border: 3px solid var(--sand);
  border-top-color: var(--amber);
  animation: pay-rotate 0.9s linear infinite;
}

@keyframes pay-rotate {
  to {
    transform: rotate(360deg);
  }
}

.pay-state {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  margin: 0 auto 12px;
  display: grid;
  place-items: center;
}

.pay-state .ico {
  width: 28px;
  height: 28px;
}

.pay-state.ok {
  background: rgba(71, 112, 76, 0.16);
  color: var(--forest);
}

.pay-state.err {
  background: rgba(200, 69, 60, 0.12);
  color: var(--brick);
}

.pay-result-title {
  text-align: center;
  font-weight: 700;
  font-size: 17px;
  margin-bottom: 4px;
}

.muted {
  color: var(--muted);
  font-size: 13px;
}

.hint {
  font-size: 12px;
  color: var(--slate);
  margin: 12px 0 0;
}

.hint.center {
  text-align: center;
  margin: 0 0 16px;
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
  color: var(--ink);
  text-align: right;
}

.kv .v.num {
  font-family: var(--font-mono);
  font-weight: 700;
}

.kv .v.coral {
  color: var(--coral);
}

.pay-foot {
  display: flex;
  gap: 10px;
  padding: 12px 16px 16px;
  border-top: 1px solid var(--border);
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

.pay-foot .btn {
  flex: 1;
  min-height: 42px;
}

.pay-tip {
  margin: 14px 0 0;
  font-size: 12px;
  color: var(--slate);
  text-align: center;
}
</style>