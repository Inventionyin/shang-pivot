<script setup lang="ts">
/**
 * 售后管理 —— 对齐原型 .stitch/原型/admin/aftersales.html。
 * 抽屉：drw-as（D6-01 售后详情抽屉，含进度时间线）；
 * 弹窗：dlg-as-approve（D6-02 同意售后）、dlg-as-reject（D6-03 拒绝售后）。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHead from '@/components/PageHead.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import ListPager from '@/components/ListPager.vue'
import DetailDrawer from '@/components/DetailDrawer.vue'
import FormDialog from '@/components/FormDialog.vue'
import NoticeDialog from '@/components/NoticeDialog.vue'
import { auditAfterSale, fetchAfterSale, fetchAfterSales } from '@/api/aftersales'
import { fetchOrder } from '@/api/orders'
import { usePager } from '@/composables/usePager'
import { unwrapPage } from '@/utils/page'
import { formatDateTime } from '@/utils/datetime'
import { resolveMediaUrl } from '@/utils/media'
import { usePermission } from '@/utils/permission'

const { hasPermission } = usePermission()
const {
  page,
  total,
  pageSize,
  totalPages,
  canPrev,
  canNext,
  resetPage,
  clampEmptyPage,
  goPrev,
  goNext,
} = usePager()

const STATUS_MAP: Record<string, string> = {
  pending: 'PENDING_AUDIT',
  refunding: 'REFUNDING',
  done: 'COMPLETED',
  rejected: 'REJECTED',
}

const STATUS_LABEL: Record<string, string> = {
  PENDING_AUDIT: '待审核',
  REFUNDING: '退款中',
  COMPLETED: '已完成',
  REJECTED: '已拒绝',
}

/** 类型文案与顾客端一致（REFUND / RETURN_REFUND） */
const TYPE_LABEL: Record<string, string> = {
  REFUND: '仅退款',
  RETURN: '退货退款',
  RETURN_REFUND: '退货退款',
  EXCHANGE: '换货',
}

type RowTone = 'amber' | 'coral' | 'forest' | 'soft-forest' | 'brick'

type AfterSaleRow = {
  id: number
  afterSaleNo: string
  orderId: string
  customer: string
  type: string
  typeTone: 'coral' | 'soft-forest'
  amount: string
  status: string
  rawStatus: string
  statusTone: RowTone
  time: string
  reason?: string
}

type TlItem = { time: string; label: string; done: boolean }

/** 关联商品行（drawer「关联商品」表） */
type ItemRow = { title: string; spec: string; price: string; qty: string; subtotal: string }

/** 抽屉展示用的售后详情 */
type AfterSaleDetail = {
  afterSaleNo: string
  typeLabel: string
  typeTone: 'coral' | 'soft-forest'
  orderNo: string
  customer: string
  refundAmount: string
  reason: string
  evidencePics: string[]
  auditRemark: string
  /** 处理意见为空时的占位文案（原型：—（审核后回写，顾客可见）） */
  auditRemarkPlaceholder: boolean
  rawStatus: string
  /** 关联订单 ID（取关联商品明细用） */
  orderIdRaw: unknown
  createdAtRaw: unknown
  auditAtRaw: unknown
  items: ItemRow[]
}

const activeTab = ref('all')
const rows = ref<AfterSaleRow[]>([])
const selected = ref<AfterSaleRow | null>(null)
const detail = ref<AfterSaleDetail | null>(null)

/* ===== 详情抽屉（drw-as · D6-01）===== */
const detailOpen = ref(false)
const detailLoading = ref(false)
const detailError = ref('')

/* ===== 同意售后（dlg-as-approve · D6-02）===== */
const approveDialog = ref(false)
const approveError = ref('')

/* ===== 拒绝售后（dlg-as-reject · D6-03）===== */
const rejectDialog = ref(false)
const rejectRemark = ref('')
const rejectError = ref('')
const rejectErrors = ref<{ remark?: string }>({})

const auditTarget = ref<AfterSaleRow | null>(null)
const auditing = ref(false)

/* ===== 当前状态不可审核 ===== */
const noAuditDialog = ref(false)

function typeLabelOf(raw: string): string {
  return TYPE_LABEL[raw] || raw || '—'
}

function typeToneOf(raw: string): 'coral' | 'soft-forest' {
  return raw === 'REFUND' ? 'soft-forest' : 'coral'
}

function statusToneOf(raw: string): RowTone {
  if (raw === 'PENDING_AUDIT') return 'amber'
  if (raw === 'REFUNDING') return 'coral'
  if (raw === 'COMPLETED') return 'soft-forest'
  return 'brick'
}

/** 金额展示：原型统一 ¥1,999.00 */
function money(v: unknown, digits = 2): string {
  if (v == null || v === '') return '—'
  const n = Number(typeof v === 'string' ? v.replace(/[¥,\s]/g, '') : v)
  if (Number.isNaN(n)) return `¥${String(v)}`
  return `¥${n.toLocaleString('zh-CN', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  })}`
}

function shortTime(v: unknown): string {
  const full = formatDateTime(v, true)
  if (!full || full === '—') return '—'
  const m = full.match(/\d{4}-(\d{2}-\d{2}) (\d{2}:\d{2})/)
  if (!m?.[1] || !m[2]) return full.slice(5, 16)
  return `${m[1].slice(5)} ${m[2]}`
}

function readErrMsg(e: unknown): string {
  if (e && typeof e === 'object') {
    const direct = (e as { msg?: unknown }).msg
    if (typeof direct === 'string' && direct) return direct
    const nested = (e as { response?: { data?: { msg?: unknown } } }).response?.data?.msg
    if (typeof nested === 'string' && nested) return nested
  }
  return e instanceof Error ? e.message : ''
}

function mapRow(r: Record<string, unknown>): AfterSaleRow {
  const raw = String(r.status ?? '')
  const typeRaw = String(r.type ?? '')
  return {
    id: Number(r.id),
    afterSaleNo: String(r.afterSaleNo ?? r.id ?? ''),
    orderId: String(r.orderNo ?? r.orderId ?? '—'),
    customer: String(r.customerName ?? r.userId ?? '—'),
    type: typeLabelOf(typeRaw),
    typeTone: typeToneOf(typeRaw),
    amount: money(r.refundAmount ?? r.amount),
    status: STATUS_LABEL[raw] || raw || '—',
    rawStatus: raw,
    statusTone: statusToneOf(raw),
    time: formatDateTime(r.createdAt ?? r.time),
    reason: r.reason != null ? String(r.reason) : undefined,
  }
}

async function load() {
  try {
    const apiStatus =
      activeTab.value === 'all' ? undefined : STATUS_MAP[activeTab.value] || activeTab.value
    const res = await fetchAfterSales({
      status: apiStatus,
      page: page.value,
      pageSize,
    })
    const pageData = unwrapPage<Record<string, unknown>>(res.data)
    if (!pageData) {
      rows.value = []
      total.value = 0
      return
    }
    total.value = pageData.total
    if (clampEmptyPage(pageData.records.length)) {
      await load()
      return
    }
    rows.value = pageData.records.map(mapRow)
    const current = selected.value
    if (current) {
      const found = rows.value.find((r) => r.id === current.id) ?? null
      selected.value = found
      // 单据已不在当前筛选内：抽屉里的内容随之失效
      if (!found) {
        detailOpen.value = false
        detail.value = null
      }
    }
  } catch {
    rows.value = []
    total.value = 0
  }
}

function switchTab(tab: string) {
  activeTab.value = tab
  resetPage()
  return load()
}

function specsText(raw: unknown): string {
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
    return (
      Object.values(raw as Record<string, unknown>)
        .filter((v) => v != null && v !== '')
        .join(' / ') || '—'
    )
  }
  return String(raw ?? '—')
}

function toItemRows(list: unknown): ItemRow[] {
  if (!Array.isArray(list)) return []
  return (list as Record<string, unknown>[]).map((it) => ({
    title: String(it.title ?? it.skuTitle ?? it.productName ?? '—'),
    spec: specsText(it.specs ?? it.spec),
    price: money(it.price, 0),
    qty: String(it.quantity ?? it.qty ?? '—'),
    subtotal: money(it.subtotal ?? it.amount, 0),
  }))
}

/** /admin/after-sales/{id} → 抽屉可直接渲染的形态 */
function buildDetail(row: AfterSaleRow, res: Record<string, unknown> | null): AfterSaleDetail | null {
  if (!res) return null
  const as =
    res.afterSale && typeof res.afterSale === 'object'
      ? (res.afterSale as Record<string, unknown>)
      : res
  const order =
    res.order && typeof res.order === 'object' ? (res.order as Record<string, unknown>) : {}
  const typeRaw = String(as.type ?? '')
  const customer = [order.customerName, order.customerPhoneMask]
    .filter((v) => v != null && v !== '')
    .join(' · ')
  const pics = Array.isArray(as.evidencePics)
    ? (as.evidencePics as unknown[]).map((p) => String(p)).filter(Boolean)
    : []
  const remark = String(as.auditRemark ?? '').trim()
  return {
    afterSaleNo: String(as.afterSaleNo ?? row.afterSaleNo ?? ''),
    typeLabel: typeRaw ? typeLabelOf(typeRaw) : row.type,
    typeTone: typeRaw ? typeToneOf(typeRaw) : row.typeTone,
    orderNo: String(order.orderNo ?? row.orderId ?? '—'),
    customer: customer || row.customer,
    refundAmount: money(as.refundAmount ?? row.amount),
    reason: String(as.reason ?? row.reason ?? '') || '—',
    evidencePics: pics,
    auditRemark: remark || '—（审核后回写，顾客可见）',
    auditRemarkPlaceholder: !remark,
    rawStatus: String(as.status ?? row.rawStatus ?? ''),
    orderIdRaw: as.orderId ?? order.id ?? null,
    createdAtRaw: as.createdAt ?? null,
    auditAtRaw: as.updatedAt ?? null,
    items: [],
  }
}

/** 关联商品不在售后接口里，单独取一次订单详情 */
async function loadRelatedItems(orderId: unknown) {
  const id = Number(orderId)
  if (!id || Number.isNaN(id)) return
  try {
    const res = await fetchOrder(id)
    const data = (res.data as Record<string, unknown>) || {}
    const order = (data.order as Record<string, unknown>) || {}
    const items = toItemRows(data.items ?? order.items)
    if (detail.value) detail.value = { ...detail.value, items }
  } catch {
    /* 取不到就只显示空态，不影响抽屉其余部分 */
  }
}

/* ===== 详情抽屉 ===== */
async function openDetail(row: AfterSaleRow) {
  selected.value = row
  detail.value = null
  detailError.value = ''
  detailOpen.value = true
  detailLoading.value = true
  try {
    const res = await fetchAfterSale(row.id)
    detail.value = buildDetail(row, (res.data as Record<string, unknown>) || null)
    if (!detail.value) detailError.value = '未获取到该售后单的详情数据'
  } catch (e: unknown) {
    detailError.value = readErrMsg(e) || '售后详情加载失败，请稍后重试'
  } finally {
    detailLoading.value = false
  }
  if (detail.value) await loadRelatedItems(detail.value.orderIdRaw)
}

/** 审核后刷新抽屉内容（同一单） */
async function refreshDetail(rowId: number) {
  if (!detailOpen.value || selected.value?.id !== rowId) return
  const row = rows.value.find((r) => r.id === rowId)
  if (!row) return
  selected.value = row
  try {
    const res = await fetchAfterSale(rowId)
    detail.value = buildDetail(row, (res.data as Record<string, unknown>) || null)
    if (detail.value) await loadRelatedItems(detail.value.orderIdRaw)
  } catch {
    /* 保留抽屉里已有内容 */
  }
}

const detailStatusLabel = computed(
  () => STATUS_LABEL[detail.value?.rawStatus ?? selected.value?.rawStatus ?? ''] || selected.value?.status || '—',
)

/** 抽屉头部徽标色调（DetailDrawer 不接受 soft-forest 档） */
const detailBadgeTone = computed<'amber' | 'coral' | 'forest' | 'brick'>(() => {
  const tone = statusToneOf(detail.value?.rawStatus ?? selected.value?.rawStatus ?? '')
  return tone === 'soft-forest' ? 'forest' : tone
})

const progress = computed((): TlItem[] => {
  const d = detail.value
  if (!d) return []
  const status = d.rawStatus
  const audited = status !== 'PENDING_AUDIT'
  const refunding = status === 'REFUNDING' || status === 'COMPLETED'
  return [
    {
      time: shortTime(d.createdAtRaw),
      label: `顾客提交售后申请 · ${d.typeLabel}`,
      done: true,
    },
    {
      time: audited ? shortTime(d.auditAtRaw) : '待操作',
      label: '后台审核 → 同意 / 拒绝',
      done: audited,
    },
    { time: '—', label: '同意后进入退款流程（沙箱退款）', done: refunding },
    { time: '—', label: '退款成功 → 已完成（回写顾客端）', done: status === 'COMPLETED' },
  ]
})

/* ===== 审核（dlg-as-approve / dlg-as-reject）===== */
const isReturnType = computed(() => {
  const t = detail.value?.typeLabel ?? auditTarget.value?.type ?? ''
  return t === '退货退款'
})

/** 抽屉底部「同意 / 拒绝」：仅待审核可审核，其余给提示 */
function openAudit(kind: 'approve' | 'reject') {
  const target = selected.value
  if (!target) return
  if (target.rawStatus !== 'PENDING_AUDIT') {
    noAuditDialog.value = true
    return
  }
  auditTarget.value = target
  if (kind === 'approve') {
    approveError.value = ''
    approveDialog.value = true
    return
  }
  rejectRemark.value = ''
  rejectError.value = ''
  rejectErrors.value = {}
  rejectDialog.value = true
}

async function submitApprove() {
  const target = auditTarget.value
  if (!target || auditing.value) return
  approveError.value = ''
  auditing.value = true
  try {
    await auditAfterSale(target.id, { approved: true })
    ElMessage.success('已同意，进入退款流程')
    approveDialog.value = false
    await load()
    await refreshDetail(target.id)
  } catch (e: unknown) {
    // 弹窗不关：后端 msg 内联显示，不能静默
    approveError.value = readErrMsg(e) || '操作失败，请稍后重试'
  } finally {
    auditing.value = false
  }
}

async function submitReject() {
  const target = auditTarget.value
  if (!target || auditing.value) return
  const errors: { remark?: string } = {}
  const remark = rejectRemark.value.trim()
  if (!remark) errors.remark = '请填写拒绝原因'
  else if (remark.length > 200) errors.remark = '处理意见为 1～200 字'
  rejectErrors.value = errors
  rejectError.value = ''
  if (Object.keys(errors).length) return

  auditing.value = true
  try {
    await auditAfterSale(target.id, { approved: false, remark })
    ElMessage.success('已拒绝')
    rejectDialog.value = false
    rejectRemark.value = ''
    await load()
    await refreshDetail(target.id)
  } catch (e: unknown) {
    // 弹窗不关：后端 msg 内联显示，不能静默
    rejectError.value = readErrMsg(e) || '操作失败，请稍后重试'
  } finally {
    auditing.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="list-page">
    <PageHead title="售后管理" sub="退款 / 退货退款 · 审核" />

    <div class="card card-pad list-card">
      <div class="tabs">
        <div class="tab" :class="{ active: activeTab === 'all' }" @click="switchTab('all')">
          全部 <span class="c">{{ total }}</span>
        </div>
        <div
          class="tab tab-warn"
          :class="{ active: activeTab === 'pending' }"
          @click="switchTab('pending')"
        >
          待审核
        </div>
        <div
          class="tab"
          :class="{ active: activeTab === 'refunding' }"
          @click="switchTab('refunding')"
        >
          退款中
        </div>
        <div class="tab" :class="{ active: activeTab === 'done' }" @click="switchTab('done')">
          已完成
        </div>
        <div
          class="tab"
          :class="{ active: activeTab === 'rejected' }"
          @click="switchTab('rejected')"
        >
          已拒绝
        </div>
      </div>

      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th class="code">售后单</th>
              <th class="code">关联订单</th>
              <th>顾客</th>
              <th>类型</th>
              <th class="num">退款金额</th>
              <th>状态</th>
              <th class="num">申请时间</th>
              <th class="col-op">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!rows.length">
              <td colspan="8" class="hint empty-cell">暂无售后单</td>
            </tr>
            <tr v-for="row in rows" :key="row.id">
              <td class="code">{{ row.afterSaleNo }}</td>
              <td class="code">{{ row.orderId }}</td>
              <td>{{ row.customer }}</td>
              <td><StatusBadge :text="row.type" :tone="row.typeTone" /></td>
              <td class="num">{{ row.amount }}</td>
              <td><StatusBadge :text="row.status" :tone="row.statusTone" /></td>
              <td class="num">{{ row.time }}</td>
              <td class="col-op">
                <button
                  class="btn btn-sm"
                  :class="
                    row.rawStatus === 'PENDING_AUDIT' && hasPermission('aftersale:audit')
                      ? 'btn-primary'
                      : 'btn-ghost'
                  "
                  type="button"
                  @click="openDetail(row)"
                >
                  {{
                    row.rawStatus === 'PENDING_AUDIT' && hasPermission('aftersale:audit')
                      ? '审核'
                      : '详情'
                  }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <ListPager
        :total="total"
        :page="page"
        :total-pages="totalPages"
        :page-size="pageSize"
        :can-prev="canPrev"
        :can-next="canNext"
        @prev="goPrev(load)"
        @next="goNext(load)"
      />
    </div>

    <!-- 售后详情抽屉（drw-as · D6-01） -->
    <DetailDrawer
      v-model="detailOpen"
      title="售后详情"
      :badge="detailStatusLabel"
      :badge-tone="detailBadgeTone"
    >
      <p v-if="detailLoading" class="hint">加载中…</p>
      <template v-else>
        <p v-if="detailError" class="error-text">{{ detailError }}</p>
        <template v-if="detail">
          <dl class="kv drawer-kv">
            <dt>售后单号</dt>
            <dd class="code">{{ detail.afterSaleNo }}</dd>
            <dt>售后类型</dt>
            <dd><StatusBadge :text="detail.typeLabel" :tone="detail.typeTone" /></dd>
            <dt>关联订单</dt>
            <dd class="code">{{ detail.orderNo }}</dd>
            <dt>顾客</dt>
            <dd>{{ detail.customer }}</dd>
            <dt>退款金额</dt>
            <dd class="num amount-refund">{{ detail.refundAmount }}</dd>
            <dt>申请原因</dt>
            <dd>{{ detail.reason }}</dd>
            <dt>凭证</dt>
            <dd>
              <span v-if="!detail.evidencePics.length" class="hint">—</span>
              <span v-else class="evidence">
                <img
                  v-for="(pic, i) in detail.evidencePics"
                  :key="i"
                  class="thumb"
                  :src="resolveMediaUrl(pic)"
                  alt=""
                />
              </span>
            </dd>
            <dt>处理意见</dt>
            <dd :class="{ hint: detail.auditRemarkPlaceholder }">{{ detail.auditRemark }}</dd>
          </dl>

          <div class="page-title drawer-section">关联商品</div>
          <table v-if="detail.items.length" class="table drawer-table">
            <thead>
              <tr>
                <th>商品</th>
                <th>规格</th>
                <th class="num">单价</th>
                <th class="num">数量</th>
                <th class="num">小计</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(it, i) in detail.items" :key="i">
                <td>{{ it.title }}</td>
                <td>{{ it.spec }}</td>
                <td class="num">{{ it.price }}</td>
                <td class="num">{{ it.qty }}</td>
                <td class="num">{{ it.subtotal }}</td>
              </tr>
            </tbody>
          </table>
          <p v-else class="hint drawer-table">暂无关联商品明细</p>

          <div class="page-title drawer-section">进度时间线</div>
          <div class="timeline">
            <div
              v-for="(tl, i) in progress"
              :key="i"
              class="tl-item"
              :class="tl.done ? 'done' : 'pending'"
            >
              <div class="tl-time">{{ tl.time }}</div>
              <div class="tl-label">{{ tl.label }}</div>
            </div>
          </div>

          <div class="alert alert-info drawer-note">
            <b>仅退款</b>不回补库存；<b>退货退款</b>在顾客寄回并确认收货后回补可售库存。退款走沙箱支付，原路退回。
          </div>
        </template>
        <p v-else class="hint">未找到该售后单</p>
      </template>

      <template #footer="{ close }">
        <button class="btn btn-secondary" type="button" @click="close">关闭</button>
        <template v-if="hasPermission('aftersale:audit')">
          <button class="btn btn-danger" type="button" @click="openAudit('reject')">拒绝</button>
          <button class="btn btn-primary" type="button" @click="openAudit('approve')">同意</button>
        </template>
      </template>
    </DetailDrawer>

    <!-- 同意售后（dlg-as-approve · D6-02） -->
    <FormDialog
      v-model="approveDialog"
      title="同意售后申请"
      icon="ok"
      width="is-md"
      cancel-text="取消"
      confirm-text="确认同意"
      :loading="auditing"
      @submit="submitApprove"
    >
      <p v-if="approveError" class="error-text">{{ approveError }}</p>
      <p>
        售后单 <b class="code">{{ auditTarget?.afterSaleNo }}</b>（{{ auditTarget?.type }} ·
        {{ auditTarget?.amount }}）将进入<b>退款流程</b>。
      </p>
      <ul class="impact">
        <li>调用沙箱支付发起退款 <b>{{ auditTarget?.amount }}</b>，原路退回</li>
        <li>售后状态 待审核 → <b>退款中</b> → 退款成功自动置为 <b>已完成</b></li>
        <li>
          {{
            isReturnType
              ? '退货退款在顾客寄回并确认收货后回补可售库存；订单与商品数据保留'
              : '仅退款不回补库存；订单与商品数据保留'
          }}
        </li>
      </ul>
      <div class="alert alert-info note-top">
        同意后不可撤销；如退款失败可重新发起或改为线下处理。处理结果会回写顾客端售后进度。
      </div>
    </FormDialog>

    <!-- 拒绝售后（dlg-as-reject · D6-03） -->
    <FormDialog
      v-model="rejectDialog"
      title="拒绝售后申请"
      icon="danger"
      icon-text="!"
      width="is-md"
      cancel-text="取消"
      confirm-text="确认拒绝"
      :loading="auditing"
      @submit="submitReject"
    >
      <p v-if="rejectError" class="error-text">{{ rejectError }}</p>
      <p class="hint reject-summary">
        售后单 <b class="code">{{ auditTarget?.afterSaleNo }}</b> · {{ auditTarget?.type }} ·
        {{ auditTarget?.amount }} · 顾客 {{ auditTarget?.customer }}
      </p>
      <div class="field">
        <label class="label">处理意见<span class="req">*</span></label>
        <textarea
          v-model="rejectRemark"
          class="textarea"
          rows="3"
          placeholder="请填写拒绝原因，例如：商品已超过七天无理由期限 / 凭证不足…"
        ></textarea>
        <span v-if="rejectErrors.remark" class="error-text">{{ rejectErrors.remark }}</span>
        <span class="hint">1～200 字，必填；该文案会展示给顾客，请勿包含内部信息。</span>
      </div>
      <div class="alert alert-warn">拒绝后售后状态置为<b>已拒绝</b>并通知顾客，顾客可补充凭证后重新申诉。</div>
    </FormDialog>

    <!-- 当前状态不可审核（对应 dlg-noop 形态） -->
    <NoticeDialog
      v-model="noAuditDialog"
      title="当前状态不可审核"
      icon="warn"
      width="is-sm"
      confirm-text="知道了"
    >
      <p>
        售后单 <b class="code">{{ selected?.afterSaleNo }}</b> 当前为
        <StatusBadge :text="selected?.status ?? '—'" :tone="selected?.statusTone ?? 'amber'" />。
      </p>
      <p class="hint">
        仅「待审核」售后单可审核。已完成 / 已拒绝的售后单不能再次审核；如需重新处理，请顾客在顾客端补充凭证后重新发起申请。
      </p>
    </NoticeDialog>
  </div>
</template>

<style scoped>
/* 原型 aftersales.html 内联样式的类化版本（不写行内 style） */
.tab-warn {
  color: var(--amber-press);
}
.empty-cell {
  text-align: center;
  padding: 24px;
}
.drawer-kv {
  grid-template-columns: 96px 1fr;
  margin-bottom: 18px;
}
.drawer-section {
  font-size: 13px;
  margin-bottom: 10px;
}
.drawer-table {
  margin-bottom: 18px;
}
.amount-refund {
  font-weight: 700;
}
.evidence {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.drawer-note {
  margin-top: 18px;
}
.note-top {
  margin-top: 12px;
}
.reject-summary {
  margin-bottom: 14px;
}
</style>