import { http, type ApiResult } from '@/utils/request'

/** V3.0 智能客服管理端接口封装（docs/04-architecture/v3.0/接口设计.md §2）。 */

export interface CsSession {
  sessionId: string
  customerId: string
  clientType: string
  status: string
  seatId?: string | null
  summary?: string | null
  startedAt?: string
  endedAt?: string
}

export interface CsSessionMessage {
  id: string
  role: string
  contentType: string
  content?: string | null
  metadata?: { items?: Array<Record<string, unknown>>; seatName?: string } | null
  degraded?: boolean
  createdAt?: string
}

export interface CsTicket {
  id: string
  sessionId?: string | null
  customerId: string
  type: string
  title: string
  status: string
  seatId?: string | null
  remark?: string | null
  slaDueAt?: string
  resolvedAt?: string
  createdAt?: string
}

export interface CsKnowledgeDoc {
  id: string
  title: string
  category: string
  status: string
  version?: number
  vectorStatus: string
  chunkCount?: number
  contentPreview?: string
  content?: string
  updatedAt?: string
}

export interface CsQuickReplyItem {
  id: string
  groupId: string
  title: string
  content: string
  sort?: number
  isRecommended?: boolean
}

export interface CsSeat {
  id: string
  adminUserId: string
  nickname: string
  status: string
  maxConcurrent: number
  ongoingCount?: number
}

// ---------- 会话 ----------

export function fetchCsSessions(params?: { status?: string; page?: number; pageSize?: number }) {
  const { pageSize, ...rest } = params || {}
  return http({ url: '/admin/customer-service/sessions', method: 'GET', params: { ...rest, size: pageSize } })
}

export function fetchCsSessionDetail(id: string | number) {
  return http({ url: `/admin/customer-service/sessions/${id}`, method: 'GET' })
}

export function claimCsSession(id: string | number) {
  return http({ url: `/admin/customer-service/sessions/${id}/claim`, method: 'POST' })
}

export function replyCsSession(id: string | number, content: string) {
  return http({ url: `/admin/customer-service/sessions/${id}/messages`, method: 'POST', data: { content } })
}

export function transferCsSession(id: string | number, targetSeatId: string | number) {
  return http({ url: `/admin/customer-service/sessions/${id}/transfer`, method: 'POST', data: { targetSeatId } })
}

export function endCsSession(
  id: string | number,
  payload: { summary?: string; createTicket?: boolean; ticketType?: string; ticketTitle?: string },
) {
  return http({ url: `/admin/customer-service/sessions/${id}/end`, method: 'POST', data: payload })
}

export function backToAiSession(id: string | number) {
  return http({ url: `/admin/customer-service/sessions/${id}/back-to-ai`, method: 'POST' })
}

// ---------- 工单 ----------

export function fetchCsTickets(params?: { status?: string; type?: string; page?: number; pageSize?: number }) {
  const { pageSize, ...rest } = params || {}
  return http({ url: '/admin/customer-service/tickets', method: 'GET', params: { ...rest, size: pageSize } })
}

export function createCsTicket(payload: {
  sessionId?: string | null
  customerId: string | number
  type: string
  title: string
  remark?: string
}) {
  return http({ url: '/admin/customer-service/tickets', method: 'POST', data: payload })
}

export function updateCsTicket(
  id: string | number,
  payload: { type: string; title: string; remark?: string },
) {
  return http({ url: `/admin/customer-service/tickets/${id}`, method: 'PUT', data: payload })
}

export function transitionCsTicket(id: string | number, target: string) {
  return http({ url: `/admin/customer-service/tickets/${id}/transition`, method: 'POST', data: { target } })
}

export function deleteCsTicket(id: string | number) {
  return http({ url: `/admin/customer-service/tickets/${id}`, method: 'DELETE' })
}

// ---------- 知识库 ----------

export function fetchCsDocs(params?: { keyword?: string; status?: string; page?: number; pageSize?: number }) {
  const { pageSize, ...rest } = params || {}
  return http({
    url: '/admin/customer-service/knowledge-docs',
    method: 'GET',
    params: { ...rest, size: pageSize },
  })
}

export function fetchCsDoc(id: string | number): Promise<ApiResult<CsKnowledgeDoc>> {
  return http({ url: `/admin/customer-service/knowledge-docs/${id}`, method: 'GET' })
}

export function createCsDoc(payload: { title: string; category: string; content: string; status: string }) {
  return http({ url: '/admin/customer-service/knowledge-docs', method: 'POST', data: payload })
}

export function updateCsDoc(
  id: string | number,
  payload: { title: string; category: string; content: string; status: string },
) {
  return http({ url: `/admin/customer-service/knowledge-docs/${id}`, method: 'PUT', data: payload })
}

export function deleteCsDoc(id: string | number) {
  return http({ url: `/admin/customer-service/knowledge-docs/${id}`, method: 'DELETE' })
}

export function rebuildCsIndex(payload: { docId?: string | number; full?: boolean }) {
  return http({ url: '/admin/customer-service/knowledge-docs/rebuild', method: 'POST', data: payload })
}

export function rebuildProductIndex() {
  return http({ url: '/admin/customer-service/product-index/rebuild', method: 'POST' })
}

// ---------- 快捷回复 ----------

export function fetchQuickReplies() {
  return http({ url: '/admin/customer-service/quick-replies', method: 'GET' })
}

export function createQuickReplyGroup(payload: { name: string; sort?: number }) {
  return http({ url: '/admin/customer-service/quick-reply-groups', method: 'POST', data: payload })
}

export function deleteQuickReplyGroup(id: string | number) {
  return http({ url: `/admin/customer-service/quick-reply-groups/${id}`, method: 'DELETE' })
}

export function createQuickReplyItem(payload: {
  groupId: string | number
  title: string
  content: string
  sort?: number
  isRecommended?: boolean
}) {
  return http({ url: '/admin/customer-service/quick-replies', method: 'POST', data: payload })
}

export function updateQuickReplyItem(
  id: string | number,
  payload: {
    groupId: string | number
    title: string
    content: string
    sort?: number
    isRecommended?: boolean
  },
) {
  return http({ url: `/admin/customer-service/quick-replies/${id}`, method: 'PUT', data: payload })
}

export function deleteQuickReplyItem(id: string | number) {
  return http({ url: `/admin/customer-service/quick-replies/${id}`, method: 'DELETE' })
}

// ---------- 坐席 ----------

export function fetchSeats() {
  return http({ url: '/admin/customer-service/seats', method: 'GET' })
}

export function updateSeatStatus(id: string | number, status: string) {
  return http({ url: `/admin/customer-service/seats/${id}/status`, method: 'PUT', data: { status } })
}

export function updateSeatConcurrency(id: string | number, maxConcurrent: number) {
  return http({ url: `/admin/customer-service/seats/${id}/max-concurrent`, method: 'PUT', data: { maxConcurrent } })
}

// ---------- 看板 / AI 配置 ----------

export function fetchCsStats(range: 'today' | 'week') {
  return http({ url: '/admin/customer-service/stats', method: 'GET', params: { range } })
}

export function fetchAiConfig() {
  return http({ url: '/admin/customer-service/ai-config', method: 'GET' })
}

export function updateAiConfig(values: Record<string, string>) {
  return http({ url: '/admin/customer-service/ai-config', method: 'PUT', data: values })
}

export function testAiConfig() {
  return http({ url: '/admin/customer-service/ai-config/test', method: 'POST' })
}
