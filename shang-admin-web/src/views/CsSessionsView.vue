<script setup lang="ts">
/**
 * 会话管理 —— V3.0 智能客服（PRD-V3.0 §8.6）。
 * 列表按状态筛选；详情抽屉内接入 / 回复 / 转接 / 结束 / 退回 AI。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHead from '@/components/PageHead.vue'
import ListPager from '@/components/ListPager.vue'
import {
  type CsSession,
  type CsSessionMessage,
  type CsSeat,
  backToAiSession,
  claimCsSession,
  endCsSession,
  fetchCsSessionDetail,
  fetchCsSessions,
  fetchSeats,
  replyCsSession,
  transferCsSession,
} from '@/api/customerService'
import { usePager } from '@/composables/usePager'
import { unwrapPage } from '@/utils/page'
import { formatDateTime } from '@/utils/datetime'
import { usePermission } from '@/utils/permission'

const { hasPermission } = usePermission()
const { page, total, pageSize, totalPages, canPrev, canNext, resetPage, clampEmptyPage, goPrev, goNext } =
  usePager()

const STATUS_LABEL: Record<string, string> = {
  AI_ONGOING: '进行中-AI',
  QUEUED: '排队中',
  HUMAN_ONGOING: '进行中-人工',
  ENDED: '已结束',
}

const status = ref('')
const rows = ref<CsSession[]>([])

const drawer = ref(false)
const detailLoading = ref(false)
const detail = ref<CsSession | null>(null)
const messages = ref<CsSessionMessage[]>([])
const replyText = ref('')
const replying = ref(false)
const seats = ref<CsSeat[]>([])
const transferDialog = ref(false)
const transferTarget = ref<string>('')
const endDialog = ref(false)
const endSummary = ref('')
const endCreateTicket = ref(false)
const endTicketType = ref('consult')
const endTicketTitle = ref('')
let pollTimer: number | null = null

async function load() {
  const res = await fetchCsSessions({ status: status.value || undefined, page: page.value, pageSize })
  const pageData = unwrapPage<CsSession>(res.data)
  if (pageData) {
    if (clampEmptyPage(pageData.records.length)) {
      await load()
      return
    }
    rows.value = pageData.records
    total.value = pageData.total
  }
}

function search() {
  resetPage()
  return load()
}

async function openDetail(row: CsSession) {
  drawer.value = true
  detailLoading.value = true
  try {
    const res = await fetchCsSessionDetail(row.sessionId)
    const data = res.data as { messages?: CsSessionMessage[] } & CsSession
    detail.value = data
    messages.value = data.messages || []
  } finally {
    detailLoading.value = false
  }
}

async function refreshDetail() {
  if (!detail.value) return
  await openDetail(detail.value)
}

async function onClaim(row: CsSession) {
  await claimCsSession(row.sessionId)
  ElMessage.success('已接入')
  await load()
  if (drawer.value && detail.value?.sessionId === row.sessionId) await refreshDetail()
}

async function onReply() {
  if (!detail.value || !replyText.value.trim()) return
  replying.value = true
  try {
    await replyCsSession(detail.value.sessionId, replyText.value.trim())
    replyText.value = ''
    await refreshDetail()
  } finally {
    replying.value = false
  }
}

async function onTransfer() {
  if (!detail.value || !transferTarget.value) return
  await transferCsSession(detail.value.sessionId, transferTarget.value)
  ElMessage.success('已转接')
  transferDialog.value = false
  await load()
  await refreshDetail()
}

async function onBackToAi() {
  if (!detail.value) return
  await ElMessageBox.confirm('确认退回智能客服自动应答？', '退回 AI')
  await backToAiSession(detail.value.sessionId)
  ElMessage.success('已退回 AI')
  await load()
  await refreshDetail()
}

async function onEnd() {
  if (!detail.value) return
  await endCsSession(detail.value.sessionId, {
    summary: endSummary.value || undefined,
    createTicket: endCreateTicket.value,
    ticketType: endCreateTicket.value ? endTicketType.value : undefined,
    ticketTitle: endCreateTicket.value ? endTicketTitle.value : undefined,
  })
  ElMessage.success('会话已结束')
  endDialog.value = false
  await load()
  await refreshDetail()
}

function openEndDialog() {
  endSummary.value = ''
  endCreateTicket.value = false
  endTicketType.value = 'consult'
  endTicketTitle.value = ''
  endDialog.value = true
}

async function loadSeats() {
  const res = await fetchSeats()
  seats.value = (res.data as CsSeat[]) || []
}

onMounted(async () => {
  await load()
  // 排队会话轮询（演示口径：5s）
  pollTimer = window.setInterval(() => {
    if (!drawer.value) void load()
  }, 5000)
})

onBeforeUnmount(() => {
  if (pollTimer) window.clearInterval(pollTimer)
})
</script>

<template>
  <div>
    <PageHead title="会话管理" sub="排队接入 / 人工回复 / 转接 / 结束" />

    <div class="toolbar">
      <el-radio-group v-model="status" @change="search">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="QUEUED">排队中</el-radio-button>
        <el-radio-button value="HUMAN_ONGOING">进行中-人工</el-radio-button>
        <el-radio-button value="AI_ONGOING">进行中-AI</el-radio-button>
        <el-radio-button value="ENDED">已结束</el-radio-button>
      </el-radio-group>
    </div>

    <el-table :data="rows" border stripe>
      <el-table-column prop="sessionId" label="会话 ID" width="170" />
      <el-table-column prop="customerId" label="会员 ID" width="170" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="row.status === 'QUEUED' ? 'warning' : row.status === 'HUMAN_ONGOING' ? 'success' : 'info'">
            {{ STATUS_LABEL[row.status] || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="summary" label="摘要" min-width="180" show-overflow-tooltip />
      <el-table-column label="开始时间" width="170">
        <template #default="{ row }">{{ formatDateTime(row.startedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          <el-button
            v-if="row.status === 'QUEUED' && hasPermission('cs:session:handle')"
            link
            type="success"
            @click="onClaim(row)"
          >
            接入
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <ListPager
      :total="total"
      :page="page"
      :page-size="pageSize"
      :total-pages="totalPages"
      :can-prev="canPrev"
      :can-next="canNext"
      @prev="goPrev(load)"
      @next="goNext(load)"
    />

    <el-drawer v-model="drawer" size="560px" :title="`会话 ${detail?.sessionId || ''}`">
      <div v-loading="detailLoading" class="detail">
        <div class="detail-meta">
          <el-tag>{{ STATUS_LABEL[detail?.status || ''] || detail?.status }}</el-tag>
          <span class="muted">会员 {{ detail?.customerId }} · {{ detail?.clientType }}</span>
        </div>

        <div class="msgs">
          <div v-for="m in messages" :key="m.id" class="msg" :class="`msg--${m.role.toLowerCase()}`">
            <div class="msg-role">{{ m.role === 'USER' ? '会员' : m.role === 'AGENT' ? '坐席' : '智能客服' }}</div>
            <div class="msg-body">{{ m.content }}</div>
          </div>
        </div>

        <template v-if="detail?.status === 'HUMAN_ONGOING' && hasPermission('cs:session:handle')">
          <div class="reply-box">
            <el-input v-model="replyText" type="textarea" :rows="2" maxlength="500" placeholder="回复会员…" />
            <div class="reply-actions">
              <el-button type="primary" :loading="replying" @click="onReply">发送回复</el-button>
              <el-button @click="transferDialog = true">转接</el-button>
              <el-button @click="onBackToAi">退回 AI</el-button>
              <el-button type="danger" @click="openEndDialog">结束</el-button>
            </div>
          </div>
        </template>
      </div>
    </el-drawer>

    <el-dialog v-model="transferDialog" title="转接给其他坐席" width="420px">
      <el-select v-model="transferTarget" placeholder="选择目标坐席" style="width: 100%">
        <el-option
          v-for="s in seats.filter((x) => x.id !== detail?.seatId && x.status === 'ONLINE')"
          :key="s.id"
          :label="`${s.nickname}（${s.ongoingCount ?? 0}/${s.maxConcurrent}）`"
          :value="s.id"
        />
      </el-select>
      <template #footer>
        <el-button @click="transferDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!transferTarget" @click="onTransfer">确认转接</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="endDialog" title="结束会话" width="460px">
      <el-form label-width="90px">
        <el-form-item label="会话摘要">
          <el-input v-model="endSummary" maxlength="255" placeholder="选填" />
        </el-form-item>
        <el-form-item label="生成工单">
          <el-switch v-model="endCreateTicket" />
        </el-form-item>
        <template v-if="endCreateTicket">
          <el-form-item label="工单类型">
            <el-select v-model="endTicketType" style="width: 100%">
              <el-option label="咨询" value="consult" />
              <el-option label="投诉" value="complaint" />
              <el-option label="退款协助" value="refund" />
              <el-option label="其他" value="other" />
            </el-select>
          </el-form-item>
          <el-form-item label="工单标题">
            <el-input v-model="endTicketTitle" maxlength="100" />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="endDialog = false">取消</el-button>
        <el-button type="danger" @click="onEnd">确认结束</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  margin-bottom: 12px;
  display: flex;
  gap: 12px;
}

.detail {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.detail-meta {
  display: flex;
  align-items: center;
  gap: 10px;
}

.muted {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.msgs {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 55vh;
  overflow: auto;
  padding: 4px;
}

.msg {
  max-width: 82%;
}

.msg--user {
  align-self: flex-end;
  text-align: right;
}

.msg--agent .msg-body {
  background: var(--el-color-success-light-9);
}

.msg-role {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  margin-bottom: 2px;
}

.msg-body {
  display: inline-block;
  background: var(--el-fill-color-light);
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 13px;
  white-space: pre-wrap;
  text-align: left;
}

.reply-box {
  border-top: 1px solid var(--el-border-color);
  padding-top: 12px;
}

.reply-actions {
  margin-top: 8px;
  display: flex;
  gap: 8px;
}
</style>
