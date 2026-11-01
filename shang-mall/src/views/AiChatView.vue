<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, onUnmounted, ref, watch } from 'vue'
import { useClientType } from '@/composables/useClientType'
import { Sparkles, Headset } from 'lucide-vue-next'
import { formatPrice } from '@/utils/format'
import { resolveMediaUrl } from '@/utils/media'
import {
  type ChatMessage,
  type ChatProductCard,
  type ChatSessionBrief,
  createSession,
  escalate,
  listMessages,
  listSessions,
  sendMessageStream,
} from '@/api/chat'

const { isH5, isWeb } = useClientType()

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
}

const sessionId = ref<string | number>('')
const sessionStatus = ref('AI_ONGOING')
const bubbles = ref<Bubble[]>([])
const input = ref('')
const sending = ref(false)
const quickQuestions = ref<string[]>([])
const showHistory = ref(false)
const historyLoading = ref(false)
const historyItems = ref<ChatSessionBrief[]>([])
const errorMsg = ref('')
const scrollHost = ref<HTMLElement>()

let seq = 0
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
onUnmounted(() => { if (queueTimer) window.clearInterval(queueTimer) })
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
  void bootstrap()
})

onBeforeUnmount(() => {
  abort?.abort()
})

async function bootstrap() {
  errorMsg.value = ''
  try {
    const res = await createSession()
    sessionId.value = res.data.sessionId
    sessionStatus.value = res.data.status
    quickQuestions.value = res.data.quickQuestions || []
    if (bubbles.value.length === 0) {
      bubbles.value.push({
        key: nextKey(),
        role: 'ASSISTANT',
        content: res.data.greeting || '您好，我是智能客服小尚~',
      })
    }
    await scrollBottom()
  } catch {
    errorMsg.value = '会话初始化失败，请稍后重试'
  }
}

function startNewSession() {
  abort?.abort()
  bubbles.value = []
  sessionStatus.value = 'AI_ONGOING'
  void bootstrap()
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
  showHistory.value = false
  errorMsg.value = ''
  try {
    const res = await listMessages(item.sessionId, 0, 50)
    sessionId.value = item.sessionId
    sessionStatus.value = item.status
    bubbles.value = (res.data || [])
      .slice()
      .reverse()
      .map((m: ChatMessage) => ({
        key: `h${m.id}`,
        role: (m.role === 'AGENT' ? 'AGENT' : m.role === 'USER' ? 'USER' : 'ASSISTANT') as Bubble['role'],
        content: m.content || '',
        reasoning: m.reasoning || undefined,
        cards: m.metadata?.items,
        degraded: m.degraded,
        seatName: m.metadata?.seatName,
      }))
    if (item.status === 'ENDED') {
      bubbles.value.push({
        key: nextKey(),
        role: 'ASSISTANT',
        content: '该会话已结束，点击右上角「新对话」继续咨询~',
      })
    }
    await scrollBottom()
  } catch {
    errorMsg.value = '历史消息加载失败'
  }
}

async function send(text?: string) {
  const content = (text ?? input.value).trim()
  if (!content || sending.value) return
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
        void scrollBottom()
      },
      onDelta: (full, delta) => {
        if (delta) received = true
        pending.content = full
        if (full) pending.reasoning = undefined
        void scrollBottom()
      },
      onCards: (items) => {
        pending.cards = items
        void scrollBottom()
      },
      onDone: (payload) => {
        pending.streaming = false
        if (!received && !pending.content) {
          pending.content = payload.degraded ? '智能助手暂时繁忙，请稍后再试~' : '（本次没有生成回答，请重试）'
        }
        pending.degraded = payload.degraded
        sessionStatus.value = payload.sessionStatus
        void scrollBottom()
      },
      onError: (payload) => {
        pending.streaming = false
        pending.content = pending.content || payload.msg || '服务开小差了，请重试'
        void scrollBottom()
      },
      onNetworkError: (msg) => {
        pending.streaming = false
        pending.content = pending.content || msg
        errorMsg.value = msg
        void scrollBottom()
      },
    },
    abort.signal,
  )
  pending.streaming = false
  sending.value = false
  abort = null
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

async function scrollBottom() {
  await nextTick()
  const host = scrollHost.value
  if (host) host.scrollTop = host.scrollHeight
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
  <div class="ai-chat" :class="{ 'is-h5': isH5, 'is-web': isWeb }">
    <header class="ai-chat__header">
      <div class="ai-chat__title">
        <span class="ai-orb ai-orb--sm" aria-hidden="true"><Sparkles :size="17" :stroke-width="2.2" /></span>
        <div>
          <div class="name">AI 助手</div>
          <div class="sub">{{ statusText || '选好物，享生活 · 智能客服小尚' }}</div>
        </div>
      </div>
      <div class="ai-chat__actions">
        <button class="link-btn" type="button" @click="openHistory">历史会话</button>
        <button class="link-btn" type="button" @click="startNewSession">新对话</button>
      </div>
    </header>

    <div ref="scrollHost" class="ai-chat__body">
      <div v-for="b in bubbles" :key="b.key" class="row" :class="`row--${b.role.toLowerCase()}`">
        <span v-if="b.role !== 'USER'" class="ai-orb ai-orb--bubble" aria-hidden="true">
          <Sparkles v-if="b.role === 'ASSISTANT'" :size="17" :stroke-width="2.2" />
          <Headset v-else :size="17" :stroke-width="2.2" />
        </span>
        <div class="bubble" :class="{ 'bubble--degraded': b.degraded }">
          <div v-if="b.degraded" class="degraded-tip">智能助手暂时繁忙，以下为兜底建议</div>
          <details v-if="b.reasoning" class="reasoning">
            <summary>思考中…（点击展开思考过程）</summary>
            <pre>{{ b.reasoning }}</pre>
          </details>
          <div v-if="b.content || !b.streaming" class="content">{{ b.content }}</div>
          <div v-else-if="!b.reasoning" class="content typing"><span />思考中…</div>
          <div v-if="b.cards?.length" class="cards">
            <RouterLink v-for="c in b.cards" :key="c.spuId" class="card" :to="`/product/${c.spuId}`">
              <div class="card__pic">
                <img v-if="c.pic" :src="resolveMediaUrl(c.pic)" :alt="c.name" />
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
      </div>

      <div v-if="quickQuestions.length && bubbles.length <= 1 && isAiOngoing" class="quick-qs">
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
          :disabled="sessionStatus === 'ENDED'"
          maxlength="500"
          placeholder="想买什么？描述需求，我来帮你挑~"
        />
        <button type="submit" :disabled="sending || !input.trim() || sessionStatus === 'ENDED'">发送</button>
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
  min-height: 100%;
}

.ai-chat.is-web {
  max-width: 860px;
  margin: 0 auto;
  min-height: calc(100vh - 220px);
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
  flex: 1;
  overflow-y: auto;
  padding: 14px 12px 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-height: calc(100vh - 260px);
  min-height: 320px;
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

.bubble {
  max-width: 78%;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 14px;
  padding: 10px 12px;
  font-size: 14px;
  line-height: 1.55;
  color: var(--ink);
  overflow-wrap: anywhere;
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
