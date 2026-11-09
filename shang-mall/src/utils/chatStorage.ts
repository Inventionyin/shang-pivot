/** C 端 AI 会话的本地存储键与生命周期辅助。 */
export const CHAT_TEST_MODE_KEY = 'shang_mall_test_mode'
export const CHAT_CLIENT_KEY = 'shangpin:chat-client-key'

/** 同一浏览器（同源 localStorage）稳定复用；用于后端 client_key 分端隔离。 */
export function getChatClientKey(): string {
  let key = localStorage.getItem(CHAT_CLIENT_KEY)
  if (!key) {
    key = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `client-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
    localStorage.setItem(CHAT_CLIENT_KEY, key)
  }
  return key
}

export function clearChatTestMode(): void {
  sessionStorage.removeItem(CHAT_TEST_MODE_KEY)
}

export function isHumanTestMode(): boolean {
  return sessionStorage.getItem(CHAT_TEST_MODE_KEY) === 'human'
}
