/**
 * 基于 fetch 的 SSE 读取器。
 * EventSource 无法携带 Authorization 头，C 端对话走 POST + ReadableStream 解析。
 * 事件协议见 docs/04-architecture/v3.0/接口设计.md §1。
 */

import { clearChatTestMode, isHumanTestMode } from '@/utils/chatStorage'

export interface SseMessage {
  event: string
  data: string
}

export interface SseHandlers {
  onMessage: (msg: SseMessage) => void
  onError?: (err: unknown) => void
}

export interface StreamPostOptions extends SseHandlers {
  signal?: AbortSignal
}

function buildHeaders(): HeadersInit {
  const token = localStorage.getItem('shang_mall_access')
  const humanTest = isHumanTestMode()
  return {
    'Content-Type': 'application/json',
    Accept: 'text/event-stream',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...(humanTest
      ? { 'X-Agent-Test': 'human', 'X-Agent-Test-Run-Id': `human-${Date.now()}` }
      : {}),
  }
}

/** 401 时与 request.ts 同口径：清 token 回登录页。 */
function handleUnauthorized() {
  localStorage.removeItem('shang_mall_access')
  localStorage.removeItem('shang_mall_refresh')
  clearChatTestMode()
  const redirect = encodeURIComponent(window.location.pathname + window.location.search)
  if (!window.location.pathname.startsWith('/login')) {
    window.location.href = `/login?redirect=${redirect}`
  }
}

/** POST 一条 SSE 流式接口并逐事件回调。返回 promise 在流结束时 resolve。 */
export async function streamPost(url: string, body: unknown, options: StreamPostOptions): Promise<void> {
  let resp: Response
  try {
    resp = await fetch(url, {
      method: 'POST',
      headers: buildHeaders(),
      body: JSON.stringify(body ?? {}),
      signal: options.signal,
    })
  } catch (err) {
    options.onError?.(err)
    return
  }

  if (resp.status === 401) {
    handleUnauthorized()
    options.onError?.(new Error('未登录'))
    return
  }
  if (!resp.ok || !resp.body) {
    let msg = `请求失败（${resp.status}）`
    try {
      const data = await resp.json()
      if (data?.msg) msg = data.msg
    } catch {
      /* 非 JSON 响应体 */
    }
    options.onError?.(new Error(msg))
    return
  }
  // 业务异常（如会话已结束）由全局异常处理返回 JSON，不是 SSE；按错误展示，避免空回复。
  const contentType = resp.headers.get('content-type') || ''
  if (!contentType.includes('text/event-stream')) {
    let msg = `请求失败（${resp.status}）`
    try {
      const data = await resp.json()
      if (data?.msg) msg = data.msg
    } catch {
      /* 非 JSON 响应体 */
    }
    options.onError?.(new Error(msg))
    return
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  // SSE 解析状态：event 行 + 多行 data
  let eventName = ''
  let dataLines: string[] = []

  const dispatch = () => {
    if (dataLines.length || eventName) {
      options.onMessage({ event: eventName || 'message', data: dataLines.join('\n') })
    }
    eventName = ''
    dataLines = []
  }

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let idx: number
      while ((idx = buffer.indexOf('\n')) >= 0) {
        let line = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 1)
        if (line.endsWith('\r')) line = line.slice(0, -1)
        if (line === '') {
          dispatch()
          continue
        }
        if (line.startsWith(':')) continue // 心跳/注释
        if (line.startsWith('event:')) {
          eventName = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          dataLines.push(line.slice(5).trimStart())
        }
      }
    }
    dispatch()
  } catch (err) {
    options.onError?.(err)
  }
}
