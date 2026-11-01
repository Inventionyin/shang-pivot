<script setup lang="ts">
/**
 * 订单列表 —— 对齐原型 .stitch/原型/admin/orders.html（V2 增量见 orders-enhanced.html）。
 * 抽屉：drw-order（D5-03 订单详情抽屉）；弹窗：dlg-ship（D5-01 订单发货）、
 *       dlg-noop（D5-02 当前状态不可发货）、dlg-export（V2 导出当前筛选）。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHead from '@/components/PageHead.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import ListPager from '@/components/ListPager.vue'
import DetailDrawer from '@/components/DetailDrawer.vue'
import FormDialog from '@/components/FormDialog.vue'
import NoticeDialog from '@/components/NoticeDialog.vue'
import JsonViewer from '@/components/JsonViewer.vue'
import { exportOrders, fetchOrder, fetchOrders, shipOrder } from '@/api/orders'
import { usePager } from '@/composables/usePager'
import { unwrapPage } from '@/utils/page'
import { formatDateTime } from '@/utils/datetime'
import { usePermission } from '@/utils/permission'

const route = useRoute()
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
  unpaid: 'PENDING_PAY',
  unshipped: 'PENDING_SHIP',
  shipping: 'PENDING_RECEIVE',
  done: 'COMPLETED',
  closed: 'CLOSED',
}

const STATUS_LABEL: Record<string, string> = {
  PENDING_PAY: '待付款',
  PENDING_SHIP: '待发货',
  PENDING_RECEIVE: '待收货',
  COMPLETED: '已完成',
  CLOSED: '已关闭',
}

const TAB_LABEL: Record<string, string> = {
  all: '全部',
  unpaid: '待付款',
  unshipped: '待发货',
  shipping: '待收货',
  done: '已完成',
  closed: '已关闭',
}

type OrderRow = {
  id: number
  orderNo: string
  customer: string
  phoneMask: string
  clientType: string
  amount: string
  status: string
  rawStatus: string
  tone: 'coral' | 'amber' | 'forest' | 'soft-forest' | 'brick'
  time: string
}

type TlItem = { time: string; label: string; done: boolean }

/** 商品项行（drawer「商品项」表 / 发货弹窗摘要行） */
type ItemRow = { title: string; spec: string; price: string; qty: string; subtotal: string }

/** 抽屉展示用的订单详情（文本已归一化；时间戳保留原值供时间线判断） */
type OrderDetail = {
  orderNo: string
  createdAt: string
  clientType: string
  receiver: string
  receiverAddress: string
  remark: string
  tradeNo: string
  paidAt: string
  rawStatus: string
  goodsAmount: string
  freight: string
  payAmount: string
  items: ItemRow[]
  raw: Record<string, unknown>
  createdAtRaw: unknown
  paidAtRaw: unknown
  shipAtRaw: unknown
  receivedAtRaw: unknown
}

const activeTab = ref('all')
const keyword = ref('')
const phoneTail = ref('')
const clientType = ref('')
const createdFrom = ref('')
const rows = ref<OrderRow[]>([])
const selected = ref<OrderRow | null>(null)
const detail = ref<OrderDetail | null>(null)

/* ===== 详情抽屉（drw-order · D5-03）===== */
const detailOpen = ref(false)
const detailLoading = ref(false)
const detailError = ref('')

/* ===== 发货（dlg-ship · D5-01）===== */
const shipDialog = ref(false)
const shipTarget = ref<OrderRow | null>(null)
const shipSaving = ref(false)
const shipError = ref('')
const shipItems = ref<ItemRow[]>([])
const shipForm = ref<{ carrier: string; trackingNo: string }>({
  carrier: '模拟快递',
  trackingNo: '',
})
const shipErrors = ref<{ trackingNo?: string }>({})

/* ===== 当前状态不可发货（dlg-noop · D5-02）===== */
const noopDialog = ref(false)
const noopTarget = ref<OrderRow | null>(null)

/* ===== 导出当前筛选（dlg-export · orders-enhanced.html V2）===== */
const exportDialog = ref(false)
const exporting = ref(false)
const exportError = ref('')

function statusTone(status: string): OrderRow['tone'] {
  if (status === 'PENDING_SHIP') return 'coral'
  if (status === 'PENDING_PAY') return 'amber'
  if (status === 'PENDING_RECEIVE') return 'forest'
  if (status === 'COMPLETED') return 'soft-forest'
  return 'brick'
}

function clientLabel(ct: string) {
  if (ct === 'web') return 'Web'
  if (ct === 'h5') return 'H5'
  return ct || '—'
}

function clientTone(ct: string): 'amber' | 'coral' | 'brick' {
  if (ct === 'web') return 'amber'
  if (ct === 'h5') return 'coral'
  return 'brick'
}

/** 金额：原型统一 ¥7,999.00；商品项表不带小数 */
function money(v: unknown, digits = 2): string {
  if (v == null || v === '') return '—'
  const n = Number(typeof v === 'string' ? v.replace(/[¥,\s]/g, '') : v)
  if (Number.isNaN(n)) return `¥${String(v)}`
  return `¥${n.toLocaleString('zh-CN', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  })}`
}

function shortTime(v: unknown) {
  const full = formatDateTime(v, true)
  if (!full || full === '—') return '—'
  // yyyy-MM-dd HH:mm:ss → MM-dd HH:mm
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

function currentFilters() {
  const apiStatus =
    activeTab.value === 'all' ? undefined : STATUS_MAP[activeTab.value] || activeTab.value
  return {
    status: apiStatus,
    keyword: keyword.value.trim() || undefined,
    phoneTail: phoneTail.value.trim() || undefined,
    clientType: clientType.value || undefined,
    createdFrom: createdFrom.value ? `${createdFrom.value}T00:00:00+08:00` : undefined,
  }
}

async function load() {
  try {
    const res = await fetchOrders({
      ...currentFilters(),
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
    rows.value = pageData.records.map((o) => {
      const raw = String(o.status ?? '')
      return {
        id: Number(o.id),
        orderNo: String(o.orderNo ?? o.id ?? ''),
        customer: String(o.customerName ?? o.receiverName ?? o.userId ?? '—'),
        phoneMask: String(o.customerPhoneMask ?? o.receiverPhone ?? '—'),
        clientType: String(o.clientType ?? ''),
        amount: money(o.payAmount ?? o.amount ?? o.totalAmount),
        status: STATUS_LABEL[raw] || raw || '—',
        rawStatus: raw,
        tone: statusTone(raw),
        time: formatDateTime(o.createdAt ?? o.time),
      }
    })
  } catch {
    rows.value = []
    total.value = 0
  }
}

function search() {
  resetPage()
  return load()
}

function switchTab(tab: string) {
  activeTab.value = tab
  resetPage()
  return load()
}

function applyRouteQuery() {
  const q = route.query
  if (typeof q.phoneTail === 'string' && q.phoneTail) phoneTail.value = q.phoneTail
  if (typeof q.clientType === 'string' && q.clientType) clientType.value = q.clientType
  if (typeof q.orderNo === 'string' && q.orderNo) keyword.value = q.orderNo
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
    price: money(it.price != null ? it.price : null, 0),
    qty: String(it.quantity ?? it.qty ?? '—'),
    subtotal: money(it.subtotal ?? it.amount ?? null, 0),
  }))
}

/** 把 /admin/orders/{id} 的返回归一化成抽屉可直接渲染的形态 */
function buildDetail(row: OrderRow, res: Record<string, unknown> | null): OrderDetail | null {
  if (!res) return null
  const order = (res.order as Record<string, unknown>) || {}
  const payment = (res.payment as Record<string, unknown>) || {}
  const addr = (order.addressSnapshot as Record<string, unknown>) || {}
  const rawStatus = String(order.status ?? row.rawStatus ?? '')
  const receiver = [addr.name ?? order.customerName, addr.phone ?? order.customerPhoneMask]
    .filter((v) => v != null && v !== '')
    .join(' · ')
  const address = [addr.province, addr.city, addr.district, addr.detail]
    .filter((v) => v != null && v !== '')
    .join('')
  const tradeNo = res.tradeNo ?? payment.tradeNo
  const paidAt = res.paidAt ?? payment.paidAt ?? order.paidAt
  return {
    orderNo: String(order.orderNo ?? res.orderNo ?? row.orderNo ?? ''),
    createdAt: formatDateTime(order.createdAt ?? res.createdAt, true),
    clientType: String(res.clientType ?? order.clientType ?? row.clientType ?? ''),
    receiver: receiver || String(order.customerName ?? row.customer ?? '—'),
    receiverAddress: String(address || order.receiverAddress || '—'),
    remark: String(order.remark ?? '') || '—',
    tradeNo: tradeNo ? String(tradeNo) : '—',
    paidAt: formatDateTime(paidAt, true),
    rawStatus: rawStatus || row.rawStatus,
    goodsAmount: money(order.totalAmount ?? res.totalAmount),
    freight: money(order.freight ?? res.freight),
    payAmount: money(order.payAmount ?? res.payAmount ?? row.amount),
    items: toItemRows(res.items ?? order.items),
    raw: res,
    createdAtRaw: order.createdAt ?? res.createdAt ?? null,
    paidAtRaw: paidAt ?? null,
    shipAtRaw: order.shipAt ?? res.shipAt ?? null,
    receivedAtRaw: order.receivedAt ?? res.receivedAt ?? null,
  }
}

/* ===== 详情抽屉 ===== */
async function openDetail(row: OrderRow) {
  selected.value = row
  detail.value = null
  detailError.value = ''
  detailOpen.value = true
  detailLoading.value = true
  try {
    const res = await fetchOrder(row.id)
    detail.value = buildDetail(row, (res.data as Record<string, unknown>) || null)
    if (!detail.value) detailError.value = '未获取到该订单的详情数据'
  } catch (e: unknown) {
    detailError.value = readErrMsg(e) || '订单详情加载失败，请稍后重试'
  } finally {
    detailLoading.value = false
  }
}

/** 打开抽屉后若同一单发货成功，同步刷新抽屉内容 */
async function refreshDetail(rowId: number) {
  if (!detailOpen.value || selected.value?.id !== rowId) return
  const row = rows.value.find((r) => r.id === rowId)
  if (!row) return
  selected.value = row
  try {
    const res = await fetchOrder(rowId)
    detail.value = buildDetail(row, (res.data as Record<string, unknown>) || null)
  } catch {
    /* 保留抽屉里已有内容 */
  }
}

const detailStatus = computed(() => detail.value?.rawStatus ?? selected.value?.rawStatus ?? '')

const detailStatusLabel = computed(
  () => STATUS_LABEL[detailStatus.value] || selected.value?.status || '—',
)

/** 抽屉头部徽标色调（DetailDrawer 不接受 soft-forest 档） */
const detailBadgeTone = computed<'amber' | 'coral' | 'forest' | 'brick'>(() => {
  const tone = statusTone(detailStatus.value)
  return tone === 'soft-forest' ? 'forest' : tone
})

const timeline = computed((): TlItem[] => {
  const d = detail.value
  if (!d) return []
  const status = d.rawStatus
  const created = shortTime(d.createdAtRaw)
  const paid = shortTime(d.paidAtRaw)
  const shipped = shortTime(d.shipAtRaw)
  const received = shortTime(d.receivedAtRaw)
  const paidDone = d.paidAtRaw != null && d.paidAtRaw !== ''
  const shipDone = ['PENDING_RECEIVE', 'COMPLETED'].includes(status) || d.shipAtRaw != null
  const receivedDone = status === 'COMPLETED' || d.receivedAtRaw != null
  const items: TlItem[] = [{ time: created, label: '提交订单 · 锁定库存', done: true }]
  if (status === 'CLOSED' && !paidDone) {
    items.push({ time: '—', label: '已关闭（未支付）', done: true })
    return items
  }
  items.push({ time: paidDone ? paid : '待操作', label: '沙箱支付成功', done: paidDone })
  items.push({
    time: shipDone ? shipped : paidDone ? '待操作' : '—',
    label: '发货 → 待收货',
    done: shipDone,
  })
  items.push({
    time: receivedDone ? received : '—',
    label: '顾客确认收货 → 已完成',
    done: receivedDone,
  })
  return items
})

/* ===== 发货（dlg-ship · D5-01）/ 不可发货（dlg-noop · D5-02）===== */
async function loadShipItems(row: OrderRow) {
  if (detail.value && selected.value?.id === row.id) {
    shipItems.value = detail.value.items
    return
  }
  try {
    const res = await fetchOrder(row.id)
    shipItems.value = buildDetail(row, (res.data as Record<string, unknown>) || null)?.items ?? []
  } catch {
    shipItems.value = []
  }
}

/** 行内「发货」与抽屉底部「发货」共用：仅待发货开表单，其余给提示 */
function openShipFlow(row?: OrderRow) {
  const target = row ?? selected.value
  if (!target) return
  shipError.value = ''
  shipErrors.value = {}
  shipItems.value = []
  if (target.rawStatus !== 'PENDING_SHIP') {
    noopTarget.value = target
    noopDialog.value = true
    return
  }
  shipTarget.value = target
  shipForm.value = { carrier: '模拟快递', trackingNo: '' }
  shipDialog.value = true
  void loadShipItems(target)
}

/** 发货弹窗摘要行：订单号 · 商品 · 规格 ×数量 · 金额 */
const shipSummary = computed(() => {
  const target = shipTarget.value
  const parts = shipItems.value.map(
    (it) => `${it.title} · ${it.spec} ×${it.qty}`,
  )
  const itemsText = parts.length ? ` · ${parts.join('、')}` : ''
  return `${itemsText} · ${target?.amount ?? '—'}`
})

async function submitShip() {
  const target = shipTarget.value
  if (!target || shipSaving.value) return
  const errors: { trackingNo?: string } = {}
  const trackingNo = shipForm.value.trackingNo.trim()
  if (!trackingNo) errors.trackingNo = '请录入运单号'
  shipErrors.value = errors
  shipError.value = ''
  if (Object.keys(errors).length) return

  shipSaving.value = true
  try {
    await shipOrder(target.id, {
      trackingNo,
      carrier: shipForm.value.carrier || undefined,
    })
    ElMessage.success('已确认发货')
    shipDialog.value = false
    await load()
    await refreshDetail(target.id)
  } catch (e: unknown) {
    // 弹窗不关：后端 msg 内联显示，不能静默
    shipError.value = readErrMsg(e) || '发货失败，请稍后重试'
  } finally {
    shipSaving.value = false
  }
}

/* ===== 导出当前筛选（dlg-export）===== */
const exportTabLabel = computed(() => TAB_LABEL[activeTab.value] ?? activeTab.value)
const exportClientLabel = computed(() =>
  clientType.value ? clientLabel(clientType.value) : '全部来源端',
)

function openExport() {
  if (!hasPermission('order:export')) {
    ElMessage.warning('无导出权限')
    return
  }
  exportError.value = ''
  exportDialog.value = true
}

async function confirmExport() {
  if (exporting.value) return
  exporting.value = true
  exportError.value = ''
  try {
    await exportOrders(currentFilters())
    ElMessage.success('导出已开始下载')
    exportDialog.value = false
  } catch (e: unknown) {
    exportError.value = readErrMsg(e) || '导出失败'
    ElMessage.error(exportError.value)
  } finally {
    exporting.value = false
  }
}

onMounted(() => {
  applyRouteQuery()
  return load()
})

watch(
  () => route.query,
  () => {
    applyRouteQuery()
    resetPage()
    return load()
  },
)
</script>

<template>
  <div class="list-page">
    <PageHead title="订单列表" sub="顾客订单 · 状态流转 · 发货 · 支持手机尾号 / 来源端筛选与导出">
      <template #actions>
        <button
          v-if="hasPermission('order:export')"
          class="btn btn-secondary btn-sm"
          type="button"
          @click="openExport"
        >
          导出当前筛选
        </button>
      </template>
    </PageHead>

    <div class="card card-pad list-card">
      <div class="tabs">
        <div class="tab" :class="{ active: activeTab === 'all' }" @click="switchTab('all')">
          全部 <span class="c">{{ total }}</span>
        </div>
        <div class="tab" :class="{ active: activeTab === 'unpaid' }" @click="switchTab('unpaid')">
          待付款
        </div>
        <div
          class="tab"
          :class="{ active: activeTab === 'unshipped' }"
          @click="switchTab('unshipped')"
        >
          待发货
        </div>
        <div
          class="tab"
          :class="{ active: activeTab === 'shipping' }"
          @click="switchTab('shipping')"
        >
          待收货
        </div>
        <div class="tab" :class="{ active: activeTab === 'done' }" @click="switchTab('done')">
          已完成
        </div>
        <div class="tab" :class="{ active: activeTab === 'closed' }" @click="switchTab('closed')">
          已关闭
        </div>
      </div>
      <div class="toolbar">
        <input
          v-model="keyword"
          class="input filter-order-no"
          placeholder="订单号"
          @keyup.enter="search"
        />
        <input
          v-model="phoneTail"
          class="input filter-phone-tail"
          placeholder="手机尾号"
          maxlength="4"
          @keyup.enter="search"
        />
        <select v-model="clientType" class="select filter-client" @change="search">
          <option value="">全部端</option>
          <option value="web">Web</option>
          <option value="h5">H5</option>
        </select>
        <input
          v-model="createdFrom"
          class="input filter-date"
          type="date"
          title="下单日起"
          @change="search"
        />
        <div class="toolbar-actions">
          <button
            class="btn btn-secondary btn-sm"
            type="button"
            @click="keyword = ''; phoneTail = ''; clientType = ''; createdFrom = ''; search()"
          >
            重置
          </button>
          <button class="btn btn-primary btn-sm" type="button" @click="search">查询</button>
        </div>
      </div>

      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th class="code">订单号</th>
              <th>顾客</th>
              <th class="code">手机</th>
              <th>端</th>
              <th class="num">金额</th>
              <th>状态</th>
              <th class="code">下单时间</th>
              <th class="col-op">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!rows.length">
              <td colspan="8" class="hint empty-cell">暂无订单</td>
            </tr>
            <tr v-for="row in rows" :key="row.id">
              <td class="code">{{ row.orderNo }}</td>
              <td>{{ row.customer }}</td>
              <td class="code">{{ row.phoneMask }}</td>
              <td>
                <StatusBadge :text="clientLabel(row.clientType)" :tone="clientTone(row.clientType)" />
              </td>
              <td class="num">{{ row.amount }}</td>
              <td><StatusBadge :text="row.status" :tone="row.tone" /></td>
              <td class="code">{{ row.time }}</td>
              <td class="col-op">
                <button
                  v-if="hasPermission('order:ship')"
                  class="btn btn-sm"
                  :class="row.rawStatus === 'PENDING_SHIP' ? 'btn-primary' : 'btn-ghost'"
                  type="button"
                  @click="openShipFlow(row)"
                >
                  发货
                </button>
                <button class="btn btn-ghost btn-sm" type="button" @click="openDetail(row)">
                  详情
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

    <!-- 订单详情抽屉（drw-order · D5-03） -->
    <DetailDrawer
      v-model="detailOpen"
      title="订单详情"
      :badge="detailStatusLabel"
      :badge-tone="detailBadgeTone"
    >
      <p v-if="detailLoading" class="hint">加载中…</p>
      <template v-else>
        <p v-if="detailError" class="error-text">{{ detailError }}</p>
        <template v-if="detail">
          <dl class="kv drawer-kv">
            <dt>订单号</dt>
            <dd class="code">{{ detail.orderNo }}</dd>
            <dt>下单时间</dt>
            <dd class="code">{{ detail.createdAt }}</dd>
            <dt>收货人</dt>
            <dd>{{ detail.receiver }}</dd>
            <dt>收货地址</dt>
            <dd>{{ detail.receiverAddress }}</dd>
            <dt>来源端</dt>
            <dd>
              <StatusBadge :text="clientLabel(detail.clientType)" :tone="clientTone(detail.clientType)" />
            </dd>
            <dt>支付单号</dt>
            <dd class="code">{{ detail.tradeNo }}</dd>
            <dt>支付时间</dt>
            <dd class="code">{{ detail.paidAt }}</dd>
            <dt>备注</dt>
            <dd class="hint">{{ detail.remark }}</dd>
          </dl>

          <div class="page-title drawer-section">商品项</div>
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
          <p v-else class="hint drawer-table">暂无明细或未加载到明细</p>

          <div class="page-title drawer-section">金额</div>
          <dl class="kv drawer-kv">
            <dt>商品金额</dt>
            <dd class="num">{{ detail.goodsAmount }}</dd>
            <dt>运费</dt>
            <dd class="num">{{ detail.freight }}</dd>
            <dt>实付</dt>
            <dd class="num amount-paid">{{ detail.payAmount }}</dd>
          </dl>

          <div class="page-title drawer-section">状态时间线</div>
          <div class="timeline">
            <div
              v-for="(tl, i) in timeline"
              :key="i"
              class="tl-item"
              :class="tl.done ? 'done' : 'pending'"
            >
              <div class="tl-time">{{ tl.time }}</div>
              <div class="tl-label">{{ tl.label }}</div>
            </div>
          </div>

          <details class="json-debug">
            <summary class="hint json-debug-summary">原始数据（JSON 调试视图）</summary>
            <JsonViewer :value="detail.raw" title="JSON" empty-text="暂无数据" />
          </details>
        </template>
        <p v-else class="hint">未找到该订单</p>
      </template>

      <template #footer="{ close }">
        <button class="btn btn-secondary" type="button" @click="close">关闭</button>
        <button
          v-if="hasPermission('order:ship')"
          class="btn btn-primary"
          type="button"
          @click="openShipFlow()"
        >
          发货
        </button>
      </template>
    </DetailDrawer>

    <!-- 订单发货（dlg-ship · D5-01） -->
    <FormDialog
      v-model="shipDialog"
      title="订单发货"
      icon="info"
      icon-text="↗"
      width="is-md"
      cancel-text="取消"
      confirm-text="确认发货"
      :loading="shipSaving"
      @submit="submitShip"
    >
      <p class="hint ship-summary">
        订单 <b class="code">{{ shipTarget?.orderNo }}</b>{{ shipSummary }}
      </p>
      <p v-if="shipError" class="error-text">{{ shipError }}</p>
      <div class="field">
        <label class="label">承运商</label>
        <select v-model="shipForm.carrier" class="select">
          <option value="模拟快递">模拟快递</option>
          <option value="顺丰速运">顺丰速运</option>
          <option value="中通快递">中通快递</option>
        </select>
        <span class="hint">不选则默认「模拟快递」</span>
      </div>
      <div class="field">
        <label class="label">运单号<span class="req">*</span></label>
        <input v-model="shipForm.trackingNo" class="input" placeholder="请录入运单号" />
        <span v-if="shipErrors.trackingNo" class="error-text">{{ shipErrors.trackingNo }}</span>
      </div>
      <div class="alert alert-info">
        发货后订单进入<b>待收货</b>，并按订单明细<b>扣减库存</b>（总库存与锁定占用同时减少）。此操作不可撤销。
      </div>
    </FormDialog>

    <!-- 当前状态不可发货（dlg-noop · D5-02） -->
    <NoticeDialog
      v-model="noopDialog"
      title="当前状态不可发货"
      icon="warn"
      width="is-sm"
      confirm-text="知道了"
    >
      <p>
        订单 <b class="code">{{ noopTarget?.orderNo }}</b> 当前为
        <StatusBadge :text="noopTarget?.status ?? '—'" :tone="noopTarget?.tone ?? 'amber'" />。
      </p>
      <p class="hint">仅「待发货」订单可发货。待付款订单需顾客完成支付，超时 15 分钟会自动关闭并释放库存。</p>
    </NoticeDialog>

    <!-- 导出当前筛选（dlg-export · orders-enhanced.html V2.0 增量） -->
    <FormDialog
      v-model="exportDialog"
      title="导出当前筛选"
      icon="info"
      icon-text="↓"
      width="is-md"
      cancel-text="取消"
      confirm-text="开始导出"
      :loading="exporting"
      @submit="confirmExport"
    >
      <p v-if="exportError" class="error-text">{{ exportError }}</p>
      <p>将按<b>当前筛选条件</b>导出订单表格文件：</p>
      <ul class="impact">
        <li>Tab：<b>{{ exportTabLabel }}</b>（{{ total }} 条）</li>
        <li>订单号：<b>{{ keyword.trim() || '（未填）' }}</b></li>
        <li>手机尾号：<b>{{ phoneTail.trim() || '（未填）' }}</b></li>
        <li>来源端：<b>{{ exportClientLabel }}</b></li>
      </ul>
      <div class="alert alert-info export-note">
        导出内容包含订单号、顾客、手机（脱敏）、来源端、金额、状态、下单时间、支付单号。导出为后台操作，会写入操作日志。
      </div>
    </FormDialog>
  </div>
</template>

<style scoped>
/* 原型 orders.html / orders-enhanced.html 内联样式的类化版本（不写行内 style） */
.filter-order-no {
  min-width: 160px;
}
.filter-phone-tail {
  width: 120px;
}
.filter-client {
  width: auto;
}
.filter-date {
  width: 160px;
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
.amount-paid {
  font-weight: 700;
}
.json-debug {
  margin-top: 18px;
}
.json-debug-summary {
  cursor: pointer;
}
.ship-summary {
  margin-bottom: 14px;
}
.export-note {
  margin-top: 12px;
}
</style>