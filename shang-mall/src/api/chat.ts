import { http } from '@/utils/request'
import { streamPost } from '@/utils/sse'
import { getChatClientKey, isHumanTestMode } from '@/utils/chatStorage'

export interface ChatProductCard {
  seq?: number
  spuId: string | number
  name: string
  subtitle?: string | null
  pic?: string | null
  price?: number | null
  priceMax?: number | null
  soldCount?: number | null
}

export interface ChatMessage {
  id: string | number
  sessionId: string | number
  role: 'USER' | 'ASSISTANT' | 'AGENT' | 'TOOL'
  contentType?: string
  content?: string | null
  reasoning?: string | null
  metadata?: { items?: ChatProductCard[]; seatName?: string } | null
  degraded?: boolean
  createdAt?: string
}

export interface ChatSessionBrief {
  sessionId: string | number
  status: string
  summary?: string | null
  startedAt?: string
  endedAt?: string
  lastMessage?: string | null
  lastMessageAt?: string | null
  isTest?: boolean
  testSource?: string | null
}

export interface CreateSessionResult {
  sessionId: string | number
  status: string
  isTest?: boolean
  testSource?: string | null
  resumed?: boolean
  previousSessionId?: string | number | null
  greeting: string
  quickQuestions: string[]
}

export interface ChatSessionState {
  sessionId: string | number
  status: string
  isTest?: boolean
  testSource?: string | null
  lastMessageAt?: string | null
  pendingAction?: ChatPendingAction | null
}

/** 写操作待确认卡片（SSE action 事件） */
export interface ChatPendingAction {
  actionId: string | number
  tool: string
  summary: string
  args?: Record<string, unknown>
  expiresAt?: string
}

export function createSession(fresh = false) {
  const humanTest = isHumanTestMode()
  return http<CreateSessionResult>({
    url: '/api/chat/sessions',
    method: 'POST',
    params: fresh ? { fresh: true } : undefined,
    headers: {
      'X-Chat-Client-Key': getChatClientKey(),
      ...(humanTest
        ? { 'X-Agent-Test': 'human', 'X-Agent-Test-Run-Id': `human-${Date.now()}` }
        : {}),
    },
  })
}

export function listSessions(page = 1, size = 10) {
  return http<{ total: number; records: ChatSessionBrief[] }>({
    url: '/api/chat/sessions',
    method: 'GET',
    params: { page, size },
  })
}

export function listMessages(sessionId: string | number, beforeId: string | number = 0, limit = 30) {
  return http<ChatMessage[]>({
    url: `/api/chat/sessions/${sessionId}/messages`,
    method: 'GET',
    params: { beforeId, limit },
  })
}

/** 会话状态 + 未过期待确认动作（用于恢复和页面打开时的状态轮询）。 */
export function getSessionState(sessionId: string | number) {
  return http<ChatSessionState>({
    url: `/api/chat/sessions/${sessionId}`,
    method: 'GET',
  })
}

export function escalate(sessionId: string | number, cancel = false) {
  return http<{ sessionId: string | number; status: string }>({
    url: `/api/chat/sessions/${sessionId}/escalate`,
    method: 'POST',
    data: { cancel },
  })
}

export function quickQuestions() {
  return http<string[]>({ url: '/api/chat/quick-questions', method: 'GET' })
}

export interface ChatActionConfirmResult {
  message?: string
}

/** 用户确认执行写操作（如取消订单） */
export function confirmChatAction(sessionId: string | number, actionId: string | number) {
  return http<ChatActionConfirmResult | null>({
    url: `/api/chat/sessions/${sessionId}/actions/${actionId}/confirm`,
    method: 'POST',
  })
}

/** 用户取消写操作 */
export function cancelChatAction(sessionId: string | number, actionId: string | number) {
  return http<ChatActionConfirmResult | null>({
    url: `/api/chat/sessions/${sessionId}/actions/${actionId}/cancel`,
    method: 'POST',
  })
}

export interface StreamEvents {
  onReasoning?: (full: string, delta: string) => void
  onDelta?: (full: string, delta: string) => void
  onCards?: (items: ChatProductCard[]) => void
  onState?: (payload: { state?: string; label?: string }) => void
  onAction?: (payload: ChatPendingAction) => void
  onDone?: (payload: { messageId: number; sessionStatus: string; degraded: boolean }) => void
  onError?: (payload: { code?: number; msg?: string }) => void
  onNetworkError?: (msg: string) => void
}

/** 发送消息并消费 SSE 事件流；resolve 于流结束。 */
export async function sendMessageStream(
  sessionId: string | number,
  content: string,
  events: StreamEvents,
  signal?: AbortSignal,
  spuId?: string | number,
): Promise<void> {
  let reasoning = ''
  let answer = ''
  await streamPost(
    `/api/chat/sessions/${sessionId}/messages`,
    spuId != null ? { content, spuId } : { content },
    {
      signal,
      onMessage: ({ event, data }) => {
        if (!data) {
          if (event === 'reasoning') {
            events.onReasoning?.(reasoning, '')
          }
          return
        }
        let payload: Record<string, unknown>
        try {
          payload = JSON.parse(data)
        } catch {
          return
        }
        switch (event) {
          case 'reasoning': {
            const delta = String(payload.delta ?? '')
            reasoning += delta
            events.onReasoning?.(reasoning, delta)
            break
          }
          case 'delta': {
            const delta = String(payload.delta ?? '')
            answer += delta
            events.onDelta?.(answer, delta)
            break
          }
          case 'cards': {
            const items = (payload.items as ChatProductCard[]) || []
            events.onCards?.(items)
            break
          }
          case 'state':
            events.onState?.(payload as { state?: string; label?: string })
            break
          case 'action':
            events.onAction?.(payload as unknown as ChatPendingAction)
            break
          case 'done':
            events.onDone?.(payload as { messageId: number; sessionStatus: string; degraded: boolean })
            break
          case 'error':
            events.onError?.(payload as { code?: number; msg?: string })
            break
          default:
            break
        }
      },
      onError: (err) => {
        events.onNetworkError?.(err instanceof Error ? err.message : '网络异常，请稍后重试')
      },
    },
  )
}
