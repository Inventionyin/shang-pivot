<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, onBeforeRouteLeave } from 'vue-router'
import { useClientType } from '@/composables/useClientType'
import { Sparkles, Headset } from 'lucide-vue-next'
import { formatPrice } from '@/utils/format'
import { resolveMediaUrl } from '@/utils/media'
import {
  type ChatMessage,
  type ChatPendingAction,
  type ChatProductCard,
  type ChatSessionBrief,
  type ChatSessionState,
  cancelChatAction,
  confirmChatAction,
  createSession,
  escalate,
  getSessionState,
  listMessages,
  listSessions,
  sendMessageStream,
} from '@/api/chat'
import { CHAT_TEST_MODE_KEY, clearChatTestMode } from '@/utils/chatStorage'

const { isH5, isWeb } = useClientType()
const route = useRoute()

type ActionStatus = 'PENDING' | 'SUBMITTING' | 'CONFIRMED' | 'CANCELLED' | 'FAILED'

interface BubbleAction extends ChatPendingAction {
  status?: ActionStatus
  resultText?: string
}

interface Bubble {
  key: string
  role: 'USER' | 'ASSISTANT' | 'AGENT'
  content: string
  reasoning?: string
  reasoningOpen?: boolean
  cards?: ChatProductCard[]
  degraded?: boolean
  streaming?: boolean
  seatName?: string
  stateLabel?: string
  action?: BubbleAction
  /** true=历史回放消息（商品卡价格/库存可能已变化）。 */
  historical?: boolean
}

const sessionId = ref<string | number>('')
const sessionStatus = ref('AI_ONGOING')
const bubbles = ref<Bubble[]>([])
const input = ref('')
const sending = ref(false)
const restoring = ref(false)
const quickQuestions = ref<string[]>([])
const showHistory = ref(false)
const historyLoading = ref(false)
const historyItems = ref<ChatSessionBrief[]>([])
const errorMsg = ref('')
const scrollHost = ref<HTMLElement>()
const restoreFailed = ref(false)
const previousSessionId = ref<string | number | null>(null)
const hasEarlier = ref(false)
const earlierLoading = ref(false)
const historyCursor = ref<string | number>(0)
const pendingActionId = ref<string | number>('')
let stateTimer: number | undefined

let seq = 0
let sessionEpoch = 0
const nextKey = () => `m${++seq}`
let abort: AbortController | null = null

const isAiOngoing = computed(() => sessionStatus.value === 'AI_ONGOING')

// 排队超时引导：排队 60 秒未接入时提示可撤回转回 AI（PRD §8.5 增量）
const queueWaitSec = ref(0)
let queueTimer: number | undefined
watch(sessionStatus, (st) => {
  if (queueTimer) { window.clearInterval(queueTimer); queueTimer = undefined }
  queueWaitSec.value = 0
  if (st === 'QUEUED') {
    queueTimer = window.setInterval(() => { queueWaitSec.value += 1 }, 1000)
  }
})
onUnmounted(() => {
  if (queueTimer) window.clearInterval(queueTimer)
  stopStatePolling()
})
const statusText = computed(() => {
  switch (sessionStatus.value) {
    case 'QUEUED':
      return '人工服务 · 排队中'
    case 'HUMAN_ONGOING':
      return '人工服务中'
    case 'ENDED':
      return '会话已结束'
    default:
      return ''
  }
})

onMounted(() => {
  // 平台“人工体验”入口：本次会话标记为 human test，并推送到测试平台真实数据看板。
  if (route.query.test === 'human') {
    sessionStorage.setItem(CHAT_TEST_MODE_KEY, 'human')
  }
  void ensureSession()
})

onBeforeRouteLeave((to) => {
  // 商品详情是 AI 会话的临时跳转：保留人工体验标记，返回时才能续接同一会话。
  // 去首页/分类/我的等页面时清除，避免后续普通会话被误标为测试流量。
  if (to.name !== 'product') {
    clearChatTestMode()
  }
})

onBeforeUnmount(() => {
  abort?.abort()
  stopStatePolling()
})

function toBubbles(messages: ChatMessage[]): Bubble[] {
  return (messages || [])
    .slice()
    .reverse()
    .map((m: ChatMessage) => ({
      key: `h${m.id}`,
      role: (m.role === 'AGENT' ? 'AGENT' : m.role === 'USER' ? 'USER' : 'ASSISTANT') as Bubble['role'],
      content: m.content || '',
      // 历史回放不回传思考链；旧接口即使返回了也不展示。
      reasoning: undefined,
      cards: m.metadata?.items,
      degraded: m.degraded,
      seatName: m.metadata?.seatName,
      historical: true,
    }))
}

function greetingBubble(content?: string): Bubble {
  return {
    key: nextKey(),
    role: 'ASSISTANT',
    content: content || '您好，我是智能客服小尚~',
  }
}

async function loadHistoryInto(targetSessionId: string | number, greeting?: string) {
  const history = await listMessages(targetSessionId, 0, 50)
  const raw = history.data || []
  const restored = toBubbles(raw)
  bubbles.value = restored.length ? restored : [greetingBubble(greeting)]
  hasEarlier.value = raw.length >= 50
  historyCursor.value = raw.length ? raw[raw.length - 1].id : 0
  restoreFailed.value = false
}

async function bootstrap(fresh = false) {
  const epoch = ++sessionEpoch
  errorMsg.value = ''
  restoreFailed.value = false
  previousSessionId.value = null
  restoring.value = true
  try {
    const res = await createSession(fresh)
    if (epoch !== sessionEpoch) return
    sessionId.value = res.data.sessionId
    sessionStatus.value = res.data.status
    quickQuestions.value = res.data.quickQuestions || []
    previousSessionId.value = fresh || res.data.resumed ? null : (res.data.previousSessionId ?? null)
    // 后端返回的测试标记是最终口径：避免 sessionStorage 里的旧标记污染普通会话。
    if (res.data.testSource === 'human') {
      sessionStorage.setItem(CHAT_TEST_MODE_KEY, 'human')
    } else {
      clearChatTestMode()
    }
    if (!fresh) {
      try {
        await loadHistoryInto(res.data.sessionId, res.data.greeting)
      } catch {
        if (epoch !== sessionEpoch) return
        // 历史加载失败时不能假装是新会话继续发：保留空态并禁用输入，等待重试。
        bubbles.value = []
        restoreFailed.value = true
        errorMsg.value = '历史消息加载失败，请重试后再继续发送'
      }
    } else {
      bubbles.value = [greetingBubble(res.data.greeting)]
      hasEarlier.value = false
      historyCursor.value = 0
    }
    await refreshSessionState()
    startStatePolling()
    await scrollBottom()
  } catch {
    if (epoch === sessionEpoch) {
      restoreFailed.value = true
      errorMsg.value = '会话初始化失败，请稍后重试'
    }
  } finally {
    if (epoch === sessionEpoch) {
      restoring.value = false
    }
  }
}

async function retryRestore() {
  if (!sessionId.value) {
    await bootstrap(false)
    return
  }
  const epoch = sessionEpoch
  restoring.value = true
  errorMsg.value = ''
  try {
    await loadHistoryInto(sessionId.value)
    await refreshSessionState()
    startStatePolling()
  } catch {
    if (epoch === sessionEpoch) {
      restoreFailed.value = true
      errorMsg.value = '历史消息加载失败，请稍后重试'
    }
  } finally {
    if (epoch === sessionEpoch) {
      restoring.value = false
    }
  }
}

/**
 * 页面打开时 bootstrap 可能尚未完成；用户抢先发送会打到 /sessions//messages（400）。
 * 用同一个 Promise 复用初始化，保证发送前一定拿到 sessionId。
 */
let bootTask: Promise<void> | null = null

function ensureSession(): Promise<void> {
  if (sessionId.value) return Promise.resolve()
  if (!bootTask) {
    bootTask = bootstrap(false).finally(() => {
      bootTask = null
    })
  }
  return bootTask
}

async function startNewSession() {
  abort?.abort()
  // 让仍在进行中的首次 bootstrap 失效，避免它晚到后覆盖新会话。
  sessionEpoch++
  bootTask = null
  stopStatePolling()
  bubbles.value = []
  sessionStatus.value = 'AI_ONGOING'
  sessionId.value = ''
  previousSessionId.value = null
  restoreFailed.value = false
  hasEarlier.value = false
  historyCursor.value = 0
  pendingActionId.value = ''
  errorMsg.value = ''
  await bootstrap(true)
}

function stopStatePolling() {
  if (stateTimer) {
    window.clearInterval(stateTimer)
    stateTimer = undefined
  }
}

function startStatePolling() {
  stopStatePolling()
  stateTimer = window.setInterval(() => {
    if (document.visibilityState === 'visible' && sessionId.value && !sending.value) {
      void refreshSessionState()
    }
  }, 30_000)
}

async function refreshSessionState() {
  if (!sessionId.value) return
  try {
    const res = await getSessionState(sessionId.value)
    const state: ChatSessionState = res.data
    if (state.status && state.status !== sessionStatus.value) {
      sessionStatus.value = state.status
      if (state.status === 'ENDED') {
        stopStatePolling()
        errorMsg.value = '会话已结束，请点击右上角「新对话」继续咨询'
      }
    }
    applyPendingAction(state.pendingAction)
  } catch {
    // 状态轮询是增强能力；网络失败不打断当前对话。
  }
}

function applyPendingAction(action?: ChatPendingAction | null) {
  if (!action || action.actionId == null) return
  if (pendingActionId.value === action.actionId) return
  pendingActionId.value = action.actionId
  const existing = bubbles.value.find((b) => b.action?.actionId === action.actionId)
  if (existing) {
    existing.action = { ...action, status: 'PENDING' }
    return
  }
  bubbles.value.push({
    key: nextKey(),
    role: 'ASSISTANT',
    content: '有一项操作待确认',
    action: { ...action, status: 'PENDING' },
  })
  void scrollBottom()
}

async function loadEarlier() {
  if (!hasEarlier.value || earlierLoading.value || !historyCursor.value) return
  earlierLoading.value = true
  const host = scrollHost.value
  const beforeHeight = host?.scrollHeight || 0
  const beforeTop = host?.scrollTop || 0
  try {
    const res = await listMessages(sessionId.value, historyCursor.value, 50)
    const raw = res.data || []
    const older = toBubbles(raw)
    if (older.length) {
      bubbles.value = [...older, ...bubbles.value]
      historyCursor.value = raw[raw.length - 1].id
      hasEarlier.value = raw.length >= 50
      await nextTick()
      if (host) host.scrollTop = host.scrollHeight - beforeHeight + beforeTop
    } else {
      hasEarlier.value = false
    }
  } catch {
    errorMsg.value = '更早消息加载失败，请稍后重试'
  } finally {
    earlierLoading.value = false
  }
}

async function openHistory() {
  showHistory.value = true
  historyLoading.value = true
  try {
    const res = await listSessions(1, 20)
    historyItems.value = res.data.records || []
  } catch {
    historyItems.value = []
  } finally {
    historyLoading.value = false
  }
}

async function loadSession(item: ChatSessionBrief) {
  abort?.abort()
  stopStatePolling()
  showHistory.value = false
  errorMsg.value = ''
  restoreFailed.value = false
  previousSessionId.value = null
  pendingActionId.value = ''
  restoring.value = true
  try {
    sessionId.value = item.sessionId
    sessionStatus.value = item.status
    if (item.testSource === 'human') {
      sessionStorage.setItem(CHAT_TEST_MODE_KEY, 'human')
    } else {
      clearChatTestMode()
    }
    await loadHistoryInto(item.sessionId)
    if (item.status === 'ENDED') {
      bubbles.value.push({
        key: nextKey(),
        role: 'ASSISTANT',
        content: '该会话已结束，点击右上角「新对话」继续咨询~',
      })
    } else {
      await refreshSessionState()
      startStatePolling()
    }
    await scrollBottom()
  } catch {
    restoreFailed.value = true
    errorMsg.value = '历史消息加载失败，请稍后重试'
  } finally {
    restoring.value = false
  }
}

async function send(text?: string) {
  const content = (text ?? input.value).trim()
  if (!content || sending.value || restoring.value || restoreFailed.value || sessionStatus.value === 'ENDED') return
  if (!sessionId.value) {
    await ensureSession()
    if (!sessionId.value) return // 初始化失败，errorMsg 已提示
  }
  input.value = ''
  errorMsg.value = ''
  bubbles.value.push({ key: nextKey(), role: 'USER', content })

  const pending: Bubble = { key: nextKey(), role: 'ASSISTANT', content: '', streaming: true }
  bubbles.value.push(pending)
  sending.value = true
  await scrollBottom()

  abort = new AbortController()
  let received = false
  await sendMessageStream(
    sessionId.value,
    content,
    {
      onReasoning: (full) => {
        pending.reasoning = full
        void scrollBottom(false)
      },
      onDelta: (full, delta) => {
        if (delta) received = true
        pending.content = full
        if (full) pending.reasoning = undefined
        void scrollBottom(false)
      },
      onCards: (items) => {
        pending.cards = items
        void scrollBottom(false)
      },
      onState: (payload) => {
        const label = payload.label || STATE_FALLBACK_LABELS[payload.state || ''] || ''
        if (label) pending.stateLabel = label
        void scrollBottom(false)
      },
      onAction: (payload) => {
        pending.action = { ...payload, status: 'PENDING' }
        void scrollBottom(false)
      },
      onDone: (payload) => {
        pending.streaming = false
        pending.stateLabel = undefined
        if (!received && !pending.content) {
          pending.content = payload.degraded ? '智能助手暂时繁忙，请稍后再试~' : '（本次没有生成回答，请重试）'
        }
        pending.degraded = payload.degraded
        sessionStatus.value = payload.sessionStatus
        // 一轮回答结束必须保证最新内容在视口内（图片/卡片随后加载也会继续贴底）。
        void scrollBottom(true)
        keepAtBottom()
      },
      onError: (payload) => {
        pending.streaming = false
        pending.stateLabel = undefined
        pending.content = pending.content || payload.msg || '服务开小差了，请重试'
        void scrollBottom(false)
      },
      onNetworkError: (msg) => {
        pending.streaming = false
        pending.stateLabel = undefined
        pending.content = pending.content || msg
        errorMsg.value = msg
        void scrollBottom(false)
      },
    },
    abort.signal,
  )
  pending.streaming = false
  pending.stateLabel = undefined
  sending.value = false
  abort = null
}

const STATE_FALLBACK_LABELS: Record<string, string> = {
  ROUTING: '正在理解您的问题…',
  TOOL_CALLING: '正在处理，请稍候…',
  ANSWERING: '正在生成回答…',
}

const TOOL_LABELS: Record<string, string> = {
  cancelOrder: '取消订单',
  createOrder: '提交订单',
  confirmOrder: '确认收货',
  payOrder: '支付订单',
  applyAfterSale: '申请售后',
  addToCart: '加入购物车',
  updateAddress: '修改地址',
}

function toolLabel(tool: string): string {
  return TOOL_LABELS[tool] || tool
}

function formatExpiry(value?: string): string {
  if (!value) return ''
  const ts = Date.parse(value)
  if (Number.isNaN(ts)) return value
  const d = new Date(ts)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 记录当前卡片正在提交哪类操作，用于按钮文案展示 */
const actionBusy = ref<Record<string, 'confirm' | 'cancel'>>({})

function isActionLocked(b: Bubble): boolean {
  const st = b.action?.status
  return st === 'SUBMITTING' || st === 'CONFIRMED' || st === 'CANCELLED'
}

function actionStatusText(b: Bubble): string {
  switch (b.action?.status) {
    case 'CONFIRMED':
      return '已确认'
    case 'CANCELLED':
      return '已取消'
    case 'FAILED':
      return '执行失败'
    default:
      return ''
  }
}

function errorText(err: unknown, fallback: string): string {
  if (err && typeof err === 'object') {
    const e = err as { msg?: unknown; message?: unknown }
    if (typeof e.msg === 'string' && e.msg) return e.msg
    if (typeof e.message === 'string' && e.message) return e.message
  }
  return fallback
}

async function confirmAction(b: Bubble) {
  const action = b.action
  if (!action || isActionLocked(b)) return
  actionBusy.value[b.key] = 'confirm'
  action.status = 'SUBMITTING'
  errorMsg.value = ''
  try {
    const res = await confirmChatAction(sessionId.value, action.actionId)
    const message = res.data?.message
    action.status = 'CONFIRMED'
    action.resultText = message || '已执行'
    pendingActionId.value = ''
    if (!message) {
      bubbles.value.push({
        key: nextKey(),
        role: 'ASSISTANT',
        content: `已为您执行：${action.summary}`,
      })
    }
    await scrollBottom()
  } catch (err) {
    action.status = 'FAILED'
    action.resultText = '执行失败'
    errorMsg.value = errorText(err, '操作执行失败，请稍后重试')
    void scrollBottom()
  } finally {
    delete actionBusy.value[b.key]
  }
}

async function cancelAction(b: Bubble) {
  const action = b.action
  if (!action || isActionLocked(b)) return
  actionBusy.value[b.key] = 'cancel'
  action.status = 'SUBMITTING'
  errorMsg.value = ''
  try {
    await cancelChatAction(sessionId.value, action.actionId)
    action.status = 'CANCELLED'
    action.resultText = '已取消'
    pendingActionId.value = ''
    bubbles.value.push({ key: nextKey(), role: 'ASSISTANT', content: '已取消该操作' })
    await scrollBottom()
  } catch (err) {
    action.status = 'FAILED'
    action.resultText = '取消失败'
    errorMsg.value = errorText(err, '取消操作失败，请稍后重试')
    void scrollBottom()
  } finally {
    delete actionBusy.value[b.key]
  }
}

async function toggleEscalate() {
  try {
    if (sessionStatus.value === 'QUEUED') {
      const res = await escalate(sessionId.value, true)
      sessionStatus.value = res.data.status
      bubbles.value.push({ key: nextKey(), role: 'ASSISTANT', content: '已撤回转人工，小尚继续为您服务~' })
    } else if (isAiOngoing.value) {
      const res = await escalate(sessionId.value, false)
      sessionStatus.value = res.data.status
      bubbles.value.push({ key: nextKey(), role: 'ASSISTANT', content: '已为您转接人工客服，正在排队，请稍候~' })
    }
    await scrollBottom()
  } catch {
    /* 状态冲突等由全局提示 */
  }
}

async function scrollBottom(force = true) {
  await nextTick()
  const host = scrollHost.value
  if (!host) return
  if (force) stickToBottom = true
  if (stickToBottom) {
    host.scrollTop = host.scrollHeight
  }
}

let stickToBottom = true

function onBodyScroll() {
  const host = scrollHost.value
  if (!host) return
  stickToBottom = host.scrollHeight - host.scrollTop - host.clientHeight < 80
}

/** 商品图片加载后高度会变化；只有用户仍在底部时才跟随，避免打断向上翻阅。 */
function onMediaLoad() {
  if (stickToBottom) {
    void scrollBottom(false)
    keepAtBottom()
  }
}

/** 卡片/图片/字体渲染可能晚于流结束；在贴底状态做几次延迟校正。 */
function keepAtBottom() {
  ;[0, 100, 300].forEach((delay) => {
    window.setTimeout(() => {
      if (stickToBottom) void scrollBottom(false)
    }, delay)
  })
}

function statusLabel(item: ChatSessionBrief): string {
  switch (item.status) {
    case 'QUEUED':
      return '排队中'
    case 'HUMAN_ONGOING':
      return '人工服务'
    case 'ENDED':
      return '已结束'
    default:
      return 'AI 应答'
  }
}
</script>

<template>
  <div class="ai-chat" :class="{ 'is-h5': isH5, 'is-web': isWeb }" data-testid="ai-chat">
    <header class="ai-chat__header">
      <div class="ai-chat__title">
        <span class="ai-orb ai-orb--sm" aria-hidden="true"><Sparkles :size="17" :stroke-width="2.2" /></span>
        <div>
          <div class="name">AI 助手</div>
          <div class="sub">{{ statusText || '选好物，享生活 · 智能客服小尚' }}</div>
        </div>
      </div>
      <div class="ai-chat__actions">
        <button class="link-btn" type="button" data-testid="ai-chat-history" @click="openHistory">历史会话</button>
        <button class="link-btn" type="button" data-testid="ai-chat-new" @click="startNewSession">新对话</button>
      </div>
    </header>

    <div ref="scrollHost" class="ai-chat__body" data-testid="ai-chat-messages" @scroll.passive="onBodyScroll">
      <div v-if="previousSessionId && !restoring" class="session-banner" data-testid="ai-chat-previous-ended">
        上一场会话已结束，已为您开启新会话。
        <button type="button" class="link-btn" @click="openHistory">查看历史会话</button>
      </div>
      <div v-if="restoreFailed" class="restore-error" data-testid="ai-chat-restore-error">
        历史消息加载失败。为避免在缺少上下文的情况下继续发送，请先重新加载。
        <button type="button" class="link-btn" @click="retryRestore">重新加载</button>
      </div>
      <button
        v-if="hasEarlier && !restoreFailed"
        type="button"
        class="load-earlier"
        data-testid="ai-chat-load-earlier"
        :disabled="earlierLoading"
        @click="loadEarlier"
      >
        {{ earlierLoading ? '加载中…' : '加载更早消息' }}
      </button>
      <div v-if="restoring && bubbles.length === 0" class="state-label">正在恢复会话…</div>
      <div
        v-for="b in bubbles"
        :key="b.key"
        class="row"
        :class="`row--${b.role.toLowerCase()}`"
        data-testid="ai-chat-bubble"
        :data-streaming="b.streaming ? 'true' : 'false'"
      >
        <span v-if="b.role !== 'USER'" class="ai-orb ai-orb--bubble" aria-hidden="true">
          <Sparkles v-if="b.role === 'ASSISTANT'" :size="17" :stroke-width="2.2" />
          <Headset v-else :size="17" :stroke-width="2.2" />
        </span>
        <div class="bubble-wrap">
          <div class="bubble" :class="{ 'bubble--degraded': b.degraded }">
            <div v-if="b.degraded" class="degraded-tip">智能助手暂时繁忙，以下为兜底建议</div>
            <details v-if="b.reasoning" class="reasoning">
              <summary>思考中…（点击展开思考过程）</summary>
              <pre>{{ b.reasoning }}</pre>
            </details>
            <div v-if="b.content || !b.streaming" class="content">{{ b.content }}</div>
            <div v-else-if="!b.reasoning" class="content typing"><span />思考中…</div>
            <div v-if="b.cards?.length" class="cards">
              <div v-if="b.historical" class="cards-history-note">历史商品卡 · 价格/库存以详情页为准</div>
              <RouterLink v-for="c in b.cards" :key="c.spuId" class="card" :to="`/product/${c.spuId}`" data-testid="ai-chat-card">
                <div class="card__pic">
                  <img v-if="c.pic" :src="resolveMediaUrl(c.pic)" :alt="c.name" @load="onMediaLoad" />
                  <span v-else class="card__placeholder">暂无图片</span>
                </div>
                <div class="card__body">
                  <div class="card__name">{{ c.name }}</div>
                  <div v-if="c.subtitle" class="card__sub">{{ c.subtitle }}</div>
                  <div class="card__meta">
                    <span class="price">¥{{ formatPrice(c.price) }}<template v-if="c.priceMax"> 起</template></span>
                    <span v-if="c.soldCount != null" class="sold">{{ c.soldCount }} 人付款</span>
                  </div>
                </div>
              </RouterLink>
            </div>
            <div v-if="b.role === 'AGENT'" class="agent-tag">人工客服{{ b.seatName ? ` · ${b.seatName}` : '' }}</div>
            <div v-else-if="b.role === 'ASSISTANT' && b.content" class="ai-gen-tag">AI 生成</div>
          </div>

          <div v-if="b.stateLabel" class="state-label">{{ b.stateLabel }}</div>

          <div
            v-if="b.action"
            class="action-card"
            :class="`action-card--${(b.action.status || 'PENDING').toLowerCase()}`"
          >
            <div class="action-card__head">
              <span class="action-card__tool">{{ toolLabel(b.action.tool) }}</span>
              <span
                v-if="actionStatusText(b)"
                class="action-card__status"
                :class="{ 'action-card__status--failed': b.action.status === 'FAILED' }"
              >
                {{ actionStatusText(b) }}
              </span>
            </div>
            <div class="action-card__summary">{{ b.action.summary }}</div>
            <div v-if="b.action.expiresAt" class="action-card__expiry">
              操作有效期至 {{ formatExpiry(b.action.expiresAt) }}，请及时处理
            </div>
            <div class="action-card__actions">
              <button
                type="button"
                class="action-btn action-btn--ghost"
                :disabled="isActionLocked(b)"
                @click="cancelAction(b)"
              >
                {{ actionBusy[b.key] === 'cancel' ? '执行中…' : '取消' }}
              </button>
              <button
                type="button"
                class="action-btn action-btn--primary"
                :disabled="isActionLocked(b)"
                @click="confirmAction(b)"
              >
                {{ actionBusy[b.key] === 'confirm' ? '执行中…' : '确认执行' }}
              </button>
            </div>
            <div v-if="b.action.resultText" class="action-card__result">{{ b.action.resultText }}</div>
          </div>
        </div>
      </div>

      <div v-if="quickQuestions.length && bubbles.length <= 1 && isAiOngoing && !restoreFailed" class="quick-qs">
        <button v-for="q in quickQuestions" :key="q" type="button" @click="send(q)">{{ q }}</button>
      </div>

      <div v-if="sessionStatus === 'QUEUED' && queueWaitSec >= 60" class="queue-hint">
        已排队 {{ Math.floor(queueWaitSec / 60) }} 分钟，坐席可能不在线。
        <button type="button" class="link-btn" @click="toggleEscalate">先转回 AI 继续聊</button>
      </div>

      <div v-if="errorMsg" class="error-bar">{{ errorMsg }}</div>
    </div>

    <footer class="ai-chat__footer">
      <div class="escalate-line">
        <button v-if="isAiOngoing" type="button" class="link-btn" @click="toggleEscalate">转人工</button>
        <button v-else-if="sessionStatus === 'QUEUED'" type="button" class="link-btn" @click="toggleEscalate">
          撤回转人工
        </button>
        <span v-else-if="sessionStatus === 'ENDED'" class="muted">会话已结束</span>
        <span v-else class="muted">人工服务中，请稍候回复</span>
      </div>
      <form class="composer" @submit.prevent="send()">
        <input
          v-model="input"
          data-testid="ai-chat-input"
          :disabled="sessionStatus === 'ENDED' || restoring || restoreFailed"
          maxlength="500"
          placeholder="想买什么？描述需求，我来帮你挑~"
        />
        <button
          type="submit"
          data-testid="ai-chat-send"
          :disabled="sending || restoring || restoreFailed || !input.trim() || sessionStatus === 'ENDED'"
        >
          发送
        </button>
      </form>
    </footer>

    <van-popup v-model:show="showHistory" position="bottom" round :style="{ maxHeight: '70%' }">
      <div class="history">
        <div class="history__title">历史会话</div>
        <div v-if="historyLoading" class="history__empty">加载中…</div>
        <div v-else-if="!historyItems.length" class="history__empty">还没有会话记录</div>
        <button
          v-for="item in historyItems"
          :key="item.sessionId"
          type="button"
          class="history__item"
          @click="loadSession(item)"
        >
          <span class="history__summary">{{ item.lastMessage || item.summary || '新会话' }}</span>
          <span class="history__status">{{ statusLabel(item) }}</span>
        </button>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.ai-chat {
  display: flex;
  flex-direction: column;
  height: calc(100dvh - 220px);
  min-height: 420px;
}

.ai-chat.is-web {
  max-width: 860px;
  margin: 0 auto;
  height: calc(100dvh - 220px);
  min-height: 460px;
}

.ai-chat.is-h5 {
  height: calc(100dvh - 180px);
  min-height: 360px;
}

/* 头部 */
.ai-chat__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-bottom: 1px solid var(--border);
  background: var(--card);
}

.ai-chat__title {
  display: flex;
  align-items: center;
  gap: 10px;
}

.ai-chat__title .name {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink);
}

.ai-chat__title .sub {
  font-size: 12px;
  color: var(--muted);
}

.ai-chat__actions {
  display: flex;
  gap: 10px;
}

.link-btn {
  border: none;
  background: none;
  color: #c8453c;
  font-size: 13px;
  cursor: pointer;
  padding: 4px 6px;
}

.muted {
  color: var(--muted);
  font-size: 12px;
}

/* 消息流 */
.ai-chat__body {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 14px 12px 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.row {
  display: flex;
  gap: 8px;
  align-items: flex-end;
}

.row--user {
  justify-content: flex-end;
}

.ai-orb {
  display: inline-grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: linear-gradient(135deg, #ffc457 0%, #ff8a5b 48%, #e0523f 100%);
  color: #fff;
  box-shadow:
    inset 0 0 0 1.5px rgba(255, 255, 255, 0.38),
    0 3px 8px rgba(224, 82, 63, 0.28);
  flex: none;
}

.ai-orb--sm {
  width: 30px;
  height: 30px;
}

.bubble-wrap {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  min-width: 0;
  max-width: 78%;
}

.row--user .bubble-wrap {
  align-items: flex-end;
}

.bubble {
  max-width: 100%;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 14px;
  padding: 10px 12px;
  font-size: 14px;
  line-height: 1.55;
  color: var(--ink);
  overflow-wrap: anywhere;
  box-sizing: border-box;
}

.row--user .bubble {
  background: #eef3ee;
  border-color: transparent;
  border-bottom-right-radius: 4px;
}

.row--assistant .bubble,
.row--agent .bubble {
  border-bottom-left-radius: 4px;
}

.bubble--degraded {
  border-color: #f0d8a8;
  background: #fffaf0;
}

.state-label {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  color: var(--muted);
}

.state-label::before {
  content: '';
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  animation: blink 1s infinite;
}

/* 写操作确认卡片 */
.action-card {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--card);
  padding: 10px 12px;
  font-size: 13px;
  color: var(--ink);
}

.action-card--confirmed {
  border-color: #cfe6c8;
  background: #f6fbf4;
}

.action-card--cancelled {
  opacity: 0.72;
}

.action-card--failed {
  border-color: #f0c7c3;
  background: #fff7f6;
}

.action-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.action-card__tool {
  font-size: 12px;
  font-weight: 600;
  color: #c8453c;
}

.action-card__status {
  flex: none;
  font-size: 11px;
  color: var(--muted);
}

.action-card__status--failed {
  color: #c8453c;
}

.action-card__summary {
  margin-top: 6px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.action-card__expiry {
  margin-top: 4px;
  font-size: 11px;
  color: var(--muted);
}

.action-card__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 10px;
}

.action-btn {
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 5px 14px;
  font-size: 12px;
  line-height: 1.3;
  background: var(--card);
  color: var(--ink);
  cursor: pointer;
}

.action-btn--primary {
  border-color: transparent;
  background: linear-gradient(135deg, #ff8a5b, #c8453c);
  color: #fff;
}

.action-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.action-card__result {
  margin-top: 8px;
  font-size: 12px;
  color: var(--muted);
}

.action-card--failed .action-card__result {
  color: #c8453c;
}

.degraded-tip {
  font-size: 12px;
  color: #b07b1f;
  margin-bottom: 6px;
}

.reasoning {
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--muted);
}

.reasoning summary {
  cursor: pointer;
  color: var(--muted);
}

.reasoning pre {
  margin: 6px 0 0;
  white-space: pre-wrap;
  max-height: 160px;
  overflow: auto;
  font-size: 12px;
  color: var(--muted);
}

.typing span {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--muted);
  margin-right: 6px;
  animation: blink 1s infinite;
}

@keyframes blink {
  50% {
    opacity: 0.2;
  }
}

.agent-tag {
  margin-top: 6px;
  font-size: 11px;
  color: var(--muted);
}

/* 商品卡片 */
.cards {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.card {
  display: flex;
  gap: 10px;
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
  background: var(--sand, #f7f5f0);
  text-decoration: none;
}

.card__pic {
  width: 84px;
  height: 84px;
  flex: none;
}

.card__pic img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.card__placeholder {
  display: grid;
  place-items: center;
  height: 100%;
  font-size: 11px;
  color: var(--muted);
}

.card__body {
  padding: 8px 10px;
  min-width: 0;
}

.card__name {
  font-size: 13px;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
}

.card__sub {
  font-size: 11px;
  color: var(--muted);
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card__meta {
  margin-top: 6px;
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}

.price {
  color: #c8453c;
  font-weight: 600;
  font-size: 14px;
}

.sold {
  font-size: 11px;
  color: var(--muted);
}

/* 快捷问 */
.quick-qs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding-left: 42px;
}

.quick-qs button {
  border: 1px solid var(--border);
  background: var(--card);
  color: var(--ink);
  border-radius: 999px;
  padding: 6px 12px;
  font-size: 12px;
  cursor: pointer;
}

.queue-hint {
  font-size: 12px;
  color: #b07b1f;
  background: #fffaf0;
  border: 1px solid #f0d8a8;
  border-radius: 8px;
  padding: 8px 10px;
}

.session-banner,
.restore-error {
  font-size: 12px;
  border-radius: 8px;
  padding: 8px 10px;
  line-height: 1.5;
}

.session-banner {
  color: #6b5a3e;
  background: #fff8ec;
  border: 1px solid #f0d8a8;
}

.restore-error {
  color: #c8453c;
  background: #fff7f6;
  border: 1px solid #f0c7c3;
}

.load-earlier {
  align-self: center;
  border: 1px solid var(--border);
  background: var(--card);
  color: var(--muted);
  border-radius: 999px;
  padding: 5px 12px;
  font-size: 12px;
  cursor: pointer;
}

.cards-history-note {
  font-size: 11px;
  color: var(--muted);
  padding: 2px 0;
}

.ai-gen-tag {
  margin-top: 6px;
  font-size: 10px;
  color: var(--muted, #9a8e7c);
  letter-spacing: 0.5px;
}

.error-bar {
  font-size: 12px;
  color: #c8453c;
  text-align: center;
}

/* 输入区 */
.ai-chat__footer {
  border-top: 1px solid var(--border);
  background: var(--card);
  padding: 8px 12px calc(10px + env(safe-area-inset-bottom));
}

.escalate-line {
  margin-bottom: 6px;
}

.composer {
  display: flex;
  gap: 8px;
}

.composer input {
  flex: 1;
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 9px 14px;
  font-size: 14px;
  outline: none;
  background: var(--sand, #f7f5f0);
}

.composer button {
  border: none;
  border-radius: 999px;
  background: linear-gradient(135deg, #ff8a5b, #c8453c);
  color: #fff;
  padding: 0 18px;
  font-size: 14px;
  cursor: pointer;
}

.composer button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 历史会话 */
.history {
  padding: 14px;
}

.history__title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 10px;
}

.history__empty {
  color: var(--muted);
  font-size: 13px;
  padding: 20px 0;
  text-align: center;
}

.history__item {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  padding: 10px 4px;
  border: none;
  border-bottom: 1px solid var(--border);
  background: none;
  cursor: pointer;
  font-size: 13px;
}

.history__summary {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ink);
}

.history__status {
  flex: none;
  color: var(--muted);
  font-size: 12px;
}
</style>
