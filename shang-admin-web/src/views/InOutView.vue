<script setup lang="ts">
/**
 * 出入库单 —— 对齐原型 .stitch/原型/admin/stock-io.html 与 dialogs-v2.html。
 * 弹窗：dlg-io-in（D2V-12 新建入库单，数量 > 0）、dlg-io-stocktake（D2V-13 盘点调整，数量差异 + 原因）；
 * 抽屉：drw-io（D2V-14 单据详情，含来源与关联订单号）。
 * 销售出库由订单发货自动生成并关联订单号，后台不能手工新建。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHead from '@/components/PageHead.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import ListPager from '@/components/ListPager.vue'
import FormDialog from '@/components/FormDialog.vue'
import DetailDrawer from '@/components/DetailDrawer.vue'
import JsonViewer from '@/components/JsonViewer.vue'
import { createInOutOrder, fetchInOutOrders } from '@/api/inout'
import { fetchStocks } from '@/api/inventory'
import { usePager } from '@/composables/usePager'
import { unwrapPage } from '@/utils/page'
import { formatDateTime } from '@/utils/datetime'
import { usePermission } from '@/utils/permission'

const TYPE_LABEL: Record<string, string> = {
  PURCHASE_IN: '采购入库',
  STOCKTAKE: '盘点调整',
  SALE_OUT: '销售出库',
}

const REASON_OPTIONS = ['盘亏', '盘盈', '破损报废']

type FlatRow = {
  billNo: string
  type: string
  skuId: string
  skuTitle: string
  qty: number
  operatorId: string
  createdAt: string
  createdAtRaw: string
  remark: string
  relatedOrderNo: string
}

type SkuOption = { skuId: string; label: string; physical: number }

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

const tab = ref('')
const billNo = ref('')
const skuId = ref('')
const fromLocal = ref('')
const toLocal = ref('')
const rows = ref<FlatRow[]>([])
const skuOptions = ref<SkuOption[]>([])

/* ===== D2V-12 新建入库单 ===== */
const createOpen = ref(false)
const createSaving = ref(false)
const createError = ref('')
const createErrors = ref<{ skuId?: string; qty?: string; remark?: string }>({})
const createForm = ref({ skuId: '', warehouse: '默认仓', qty: '1', remark: '' })

/* ===== D2V-13 盘点调整 ===== */
const stOpen = ref(false)
const stSaving = ref(false)
const stError = ref('')
const stErrors = ref<{ skuId?: string; actual?: string }>({})
const stForm = ref({ skuId: '', actual: '', reason: '盘亏' })

/* ===== D2V-14 单据详情抽屉 ===== */
const detailOpen = ref(false)
const detailBillNo = ref('')

const billRows = computed(() => rows.value.filter((r) => r.billNo === detailBillNo.value))
const detailBill = computed(() => billRows.value[0] ?? null)
const detailTotal = computed(() => billRows.value.reduce((sum, r) => sum + r.qty, 0))
const detailSkuList = computed(() => billRows.value.map((r) => r.skuId).join(' · '))

/** 盘点：账面数量取当前总库存（只读） */
const bookQty = computed(() => {
  const hit = skuOptions.value.find((o) => o.skuId === stForm.value.skuId)
  return hit ? hit.physical : null
})
const stDiff = computed(() => {
  if (stForm.value.actual === '' || bookQty.value == null) return null
  const a = Number(stForm.value.actual)
  if (Number.isNaN(a)) return null
  return a - bookQty.value
})

function signed(n: number): string {
  if (n > 0) return `+${n}`
  if (n < 0) return `−${Math.abs(n)}`
  return '0'
}

function typeTone(type: string): 'forest' | 'amber' | 'coral' {
  if (type === 'PURCHASE_IN') return 'forest'
  if (type === 'STOCKTAKE') return 'amber'
  return 'coral'
}

function sourceLabel(type: string): string {
  if (type === 'SALE_OUT') return '订单发货'
  if (type === 'STOCKTAKE') return '后台盘点'
  return '后台新建'
}

function readErrMsg(e: unknown): string {
  if (e && typeof e === 'object') {
    const direct = (e as { msg?: unknown }).msg
    if (typeof direct === 'string' && direct) return direct
    const nested = (e as { response?: { data?: { msg?: unknown } } }).response?.data?.msg
    if (typeof nested === 'string' && nested) return nested
  }
  return ''
}

function toIso(local: string, endOfDay: boolean): string | undefined {
  if (!local.trim()) return undefined
  // datetime-local → +08:00
  const base = local.includes('T') ? local : `${local}T${endOfDay ? '23:59' : '00:00'}`
  return `${base}:00+08:00`
}

async function load() {
  try {
    const res = await fetchInOutOrders({
      type: tab.value || undefined,
      billNo: billNo.value.trim() || undefined,
      skuId: skuId.value.trim() || undefined,
      from: toIso(fromLocal.value, false),
      to: toIso(toLocal.value, true),
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
    const flat: FlatRow[] = []
    for (const rec of pageData.records) {
      const bill = (rec.bill || rec) as Record<string, unknown>
      const items = Array.isArray(rec.items) ? (rec.items as Record<string, unknown>[]) : []
      const raw = String(bill.createdAt ?? '')
      if (!items.length) {
        flat.push({
          billNo: String(bill.billNo ?? ''),
          type: String(bill.type ?? ''),
          skuId: '—',
          skuTitle: '—',
          qty: 0,
          operatorId: bill.operatorId != null ? String(bill.operatorId) : '系统',
          createdAt: formatDateTime(bill.createdAt),
          createdAtRaw: raw,
          remark: String(bill.remark ?? ''),
          relatedOrderNo: String(bill.relatedOrderNo ?? ''),
        })
        continue
      }
      for (const it of items) {
        flat.push({
          billNo: String(bill.billNo ?? ''),
          type: String(bill.type ?? ''),
          skuId: String(it.skuId ?? '—'),
          skuTitle: String(it.skuTitle ?? '—'),
          qty: Number(it.qty ?? 0),
          operatorId: bill.operatorId != null ? String(bill.operatorId) : '系统·发货',
          createdAt: formatDateTime(bill.createdAt),
          createdAtRaw: raw,
          remark: String(it.remark || bill.remark || ''),
          relatedOrderNo: String(bill.relatedOrderNo ?? ''),
        })
      }
    }
    rows.value = flat
  } catch {
    rows.value = []
    total.value = 0
  }
}

/** SKU 下拉：按 SKU 入库，一次一单 */
async function loadSkus() {
  try {
    const res = await fetchStocks({ page: 1, pageSize: 200 })
    const pageData = unwrapPage<Record<string, unknown>>(res.data)
    skuOptions.value = (pageData?.records ?? []).map((r) => ({
      skuId: String(r.skuId ?? ''),
      label: `${r.skuId ?? '—'} · ${r.skuTitle ?? r.name ?? '—'}`,
      physical: Number(r.physicalStock ?? r.physical ?? 0),
    }))
  } catch {
    skuOptions.value = []
  }
}

function search() {
  resetPage()
  return load()
}

function switchTab(t: string) {
  tab.value = t
  resetPage()
  return load()
}

function openCreate() {
  if (!hasPermission('stock:io')) {
    ElMessage.warning('无权限')
    return
  }
  createForm.value = {
    skuId: skuOptions.value[0]?.skuId ?? '',
    warehouse: '默认仓',
    qty: '1',
    remark: '',
  }
  createErrors.value = {}
  createError.value = ''
  createOpen.value = true
}

async function submitCreate() {
  const errors: { skuId?: string; qty?: string; remark?: string } = {}
  if (!createForm.value.skuId) errors.skuId = '请选择 SKU'
  const qty = Number(createForm.value.qty)
  if (createForm.value.qty === '' || Number.isNaN(qty)) errors.qty = '请填写入库数量'
  else if (!Number.isInteger(qty) || qty <= 0) errors.qty = '入库数量必须为 > 0 的整数'
  if (createForm.value.remark.length > 100) errors.remark = '备注 ≤100 字符'
  createErrors.value = errors
  createError.value = ''
  if (Object.keys(errors).length) return

  createSaving.value = true
  try {
    await createInOutOrder({
      type: 'PURCHASE_IN',
      remark: createForm.value.remark.trim() || undefined,
      items: [{ skuId: Number(createForm.value.skuId), qty }],
    })
    ElMessage.success('已生成采购入库单')
    createOpen.value = false
    await load()
  } catch (e: unknown) {
    createError.value = readErrMsg(e) || '入库失败，请稍后重试'
  } finally {
    createSaving.value = false
  }
}

function openStocktake() {
  if (!hasPermission('stock:io')) {
    ElMessage.warning('无权限')
    return
  }
  stForm.value = { skuId: skuOptions.value[0]?.skuId ?? '', actual: '', reason: '盘亏' }
  stErrors.value = {}
  stError.value = ''
  stOpen.value = true
}

async function submitStocktake() {
  const errors: { skuId?: string; actual?: string } = {}
  if (!stForm.value.skuId) errors.skuId = '请选择 SKU'
  const actual = Number(stForm.value.actual)
  if (stForm.value.actual === '' || Number.isNaN(actual)) errors.actual = '请填写实盘数量'
  else if (!Number.isInteger(actual) || actual < 0) errors.actual = '实盘数量必须为 ≥ 0 的整数'
  else if (bookQty.value == null) errors.skuId = '未取到该 SKU 的账面数量，请重新选择'
  else if (stDiff.value === 0) errors.actual = '实盘与账面一致，数量差异为 0，无需调整'
  stErrors.value = errors
  stError.value = ''
  if (Object.keys(errors).length) return

  const diff = stDiff.value
  if (diff == null) return
  stSaving.value = true
  try {
    await createInOutOrder({
      type: 'STOCKTAKE',
      remark: stForm.value.reason,
      items: [{ skuId: Number(stForm.value.skuId), qty: diff, remark: stForm.value.reason }],
    })
    ElMessage.success('已生成盘点单')
    stOpen.value = false
    await load()
  } catch (e: unknown) {
    stError.value = readErrMsg(e) || '盘点调整失败，请稍后重试'
  } finally {
    stSaving.value = false
  }
}

function openDetail(row: FlatRow) {
  detailBillNo.value = row.billNo
  detailOpen.value = true
}

/** 抽屉「来源说明」时间线：按单据类型回显来源链路 */
const detailTimeline = computed(() => {
  const b = detailBill.value
  if (!b) return []
  const t = b.createdAt
  const total = signed(detailTotal.value)
  if (b.type === 'SALE_OUT') {
    return [
      { time: t, label: '订单发货 · 自动生成销售出库单' },
      { time: t, label: `库存扣减 ${total}，写入操作日志` },
    ]
  }
  if (b.type === 'STOCKTAKE') {
    return [
      { time: t, label: '后台「盘点调整」提交 · 生成盘点单' },
      { time: t, label: `库存调整 ${total}，写入操作日志` },
    ]
  }
  return [
    { time: t, label: '后台「新建入库」提交 · 生成采购入库单' },
    { time: t, label: `库存增加 ${total}，写入操作日志` },
  ]
})

const detailRaw = computed(() => {
  const b = detailBill.value
  if (!b) return null
  return {
    billNo: b.billNo,
    type: b.type,
    operatorId: b.operatorId,
    source: sourceLabel(b.type),
    relatedOrderNo: b.relatedOrderNo || null,
    remark: b.remark || null,
    createdAt: b.createdAtRaw,
    items: billRows.value.map((r) => ({ skuId: Number(r.skuId), qty: r.qty })),
  }
})

onMounted(async () => {
  await Promise.all([load(), loadSkus()])
})
</script>

<template>
  <PageHead
    title="出入库单"
    sub="采购入库 / 盘点调整 / 销售出库三本流水 · 订单发货自动生成出库单并关联订单号 · 每次库存变动可追溯"
  >
    <template #actions>
      <div v-if="hasPermission('stock:io')" class="head-actions">
        <button class="btn btn-secondary" type="button" @click="openStocktake">盘点调整</button>
        <button class="btn btn-primary" type="button" @click="openCreate">+ 新建入库</button>
      </div>
    </template>
  </PageHead>

  <div class="card card-pad list-card">
    <div class="tabs">
      <div class="tab" :class="{ active: tab === '' }" @click="switchTab('')">全部</div>
      <div class="tab" :class="{ active: tab === 'PURCHASE_IN' }" @click="switchTab('PURCHASE_IN')">
        采购入库
      </div>
      <div class="tab" :class="{ active: tab === 'STOCKTAKE' }" @click="switchTab('STOCKTAKE')">
        盘点调整
      </div>
      <div class="tab" :class="{ active: tab === 'SALE_OUT' }" @click="switchTab('SALE_OUT')">
        销售出库
      </div>
    </div>

    <div class="toolbar">
      <input v-model="billNo" class="input" placeholder="单号 / SKU" @keyup.enter="search" />
      <input v-model="skuId" class="input input-narrow" placeholder="SKU ID" @keyup.enter="search" />
      <input v-model="fromLocal" class="input input-date" type="datetime-local" title="起始时间" />
      <input v-model="toLocal" class="input input-date" type="datetime-local" title="结束时间" />
      <div class="toolbar-actions">
        <button
          class="btn btn-secondary btn-sm"
          type="button"
          @click="billNo = ''; skuId = ''; fromLocal = ''; toLocal = ''; search()"
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
            <th class="code">单据号</th>
            <th>类型</th>
            <th>来源</th>
            <th class="code">SKU</th>
            <th>商品</th>
            <th class="num">数量</th>
            <th>操作人</th>
            <th class="code">时间</th>
            <th class="col-op">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!rows.length">
            <td colspan="9" class="hint empty-cell">暂无单据</td>
          </tr>
          <tr v-for="(row, i) in rows" :key="`${row.billNo}-${row.skuId}-${i}`">
            <td class="code">{{ row.billNo }}</td>
            <td>
              <StatusBadge :text="TYPE_LABEL[row.type] || row.type" :tone="typeTone(row.type)" />
            </td>
            <td>{{ sourceLabel(row.type) }}</td>
            <td class="code">{{ row.skuId }}</td>
            <td>{{ row.skuTitle }}</td>
            <td class="num">{{ signed(row.qty) }}</td>
            <td>{{ row.operatorId }}</td>
            <td class="code">{{ row.createdAt }}</td>
            <td class="col-op">
              <button class="btn btn-ghost btn-sm" type="button" @click="openDetail(row)">详情</button>
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

  <!-- 新建入库单（D2V-12 · dlg-io-in）：数量 > 0 -->
  <FormDialog
    v-model="createOpen"
    title="新建入库单"
    icon="info"
    icon-text="↓"
    width="is-md"
    cancel-text="取消"
    confirm-text="确认入库"
    :loading="createSaving"
    @submit="submitCreate"
  >
    <p v-if="createError" class="error-text">{{ createError }}</p>
    <div class="field">
      <label class="label">SKU<span class="req">*</span></label>
      <select v-model="createForm.skuId" class="select">
        <option value="">请选择 SKU</option>
        <option v-for="s in skuOptions" :key="s.skuId" :value="s.skuId">{{ s.label }}</option>
      </select>
      <span class="hint">按 SKU 入库，一次一单；同一单多 SKU 请分次录入</span>
      <span v-if="createErrors.skuId" class="error-text">{{ createErrors.skuId }}</span>
    </div>
    <div class="form-grid">
      <div class="field">
        <label class="label">仓库</label>
        <select v-model="createForm.warehouse" class="select">
          <option value="默认仓">默认仓</option>
        </select>
        <span class="hint">当前仅一个仓库</span>
      </div>
      <div class="field">
        <label class="label">入库数量<span class="req">*</span></label>
        <input v-model="createForm.qty" class="input" type="number" min="1" step="1" />
        <span class="hint">必须为 <b>&gt; 0</b> 的整数</span>
        <span v-if="createErrors.qty" class="error-text">{{ createErrors.qty }}</span>
      </div>
    </div>
    <div class="field">
      <label class="label">备注</label>
      <input v-model="createForm.remark" class="input" maxlength="100" />
      <span class="hint">≤100 字符，随单据一并保存</span>
      <span v-if="createErrors.remark" class="error-text">{{ createErrors.remark }}</span>
    </div>
    <div class="alert alert-info">
      保存后立即增加该 SKU 库存并生成<b>采购入库</b>流水，单据号形如
      <span class="code">PIyyyyMMddNNNN</span>；单据生成后不可修改，录错请用「盘点调整」冲正。
    </div>
  </FormDialog>

  <!-- 盘点调整（D2V-13 · dlg-io-stocktake）：数量差异 + 原因 -->
  <FormDialog
    v-model="stOpen"
    title="盘点调整"
    icon="warn"
    icon-text="±"
    width="is-md"
    cancel-text="取消"
    confirm-text="确认调整"
    :loading="stSaving"
    @submit="submitStocktake"
  >
    <p v-if="stError" class="error-text">{{ stError }}</p>
    <div class="field">
      <label class="label">SKU<span class="req">*</span></label>
      <select v-model="stForm.skuId" class="select">
        <option value="">请选择 SKU</option>
        <option v-for="s in skuOptions" :key="s.skuId" :value="s.skuId">{{ s.label }}</option>
      </select>
      <span v-if="stErrors.skuId" class="error-text">{{ stErrors.skuId }}</span>
    </div>
    <div class="form-grid">
      <div class="field">
        <label class="label">账面数量</label>
        <input
          class="input"
          type="number"
          :value="bookQty ?? ''"
          readonly
        />
        <span class="hint">取当前总库存，只读</span>
      </div>
      <div class="field">
        <label class="label">实盘数量<span class="req">*</span></label>
        <input v-model="stForm.actual" class="input" type="number" min="0" step="1" />
        <span class="hint">必须为 ≥ 0 的整数</span>
        <span v-if="stErrors.actual" class="error-text">{{ stErrors.actual }}</span>
      </div>
      <div class="field">
        <label class="label">数量差异</label>
        <input class="input" type="text" :value="stDiff == null ? '' : signed(stDiff)" readonly />
        <span class="hint">实盘 − 账面，系统自动计算</span>
      </div>
      <div class="field">
        <label class="label">差异原因<span class="req">*</span></label>
        <select v-model="stForm.reason" class="select">
          <option v-for="r in REASON_OPTIONS" :key="r" :value="r">{{ r }}</option>
        </select>
      </div>
    </div>
    <div class="alert alert-warn">
      差异为负数（盘亏）会<b>减少总库存</b>；调整后若总库存小于锁定占用，本次调整将被拒绝。保存后生成
      <span class="code">STyyyyMMddNNNN</span> 盘点单。
    </div>
  </FormDialog>

  <!-- 单据详情抽屉（D2V-14 · drw-io）：含来源与关联订单号 -->
  <DetailDrawer
    v-model="detailOpen"
    title="单据详情"
    :badge="detailBill ? TYPE_LABEL[detailBill.type] || detailBill.type : ''"
    :badge-tone="detailBill ? typeTone(detailBill.type) : 'amber'"
  >
    <template v-if="detailBill">
      <dl class="kv drawer-kv">
        <dt>类型</dt>
        <dd>{{ TYPE_LABEL[detailBill.type] || detailBill.type }}（{{ detailBill.type }}）</dd>
        <dt>单据号</dt>
        <dd class="code">{{ detailBill.billNo }}</dd>
        <dt>来源</dt>
        <dd>{{ sourceLabel(detailBill.type) }}</dd>
        <dt>SKU</dt>
        <dd class="code">{{ detailSkuList }}</dd>
        <dt>数量合计</dt>
        <dd class="num drawer-total">{{ signed(detailTotal) }}</dd>
        <dt>关联订单号</dt>
        <dd :class="detailBill.relatedOrderNo ? 'code' : 'hint'">
          {{ detailBill.relatedOrderNo || '—（销售出库单才有）' }}
        </dd>
        <dt>操作人</dt>
        <dd>{{ detailBill.operatorId }}</dd>
        <dt>时间</dt>
        <dd class="code">{{ formatDateTime(detailBill.createdAtRaw, true) }}</dd>
        <dt>备注</dt>
        <dd>{{ detailBill.remark || '—' }}</dd>
      </dl>

      <div class="page-title drawer-section">单据明细</div>
      <table class="table drawer-table">
        <thead>
          <tr>
            <th class="code">SKU</th>
            <th>商品</th>
            <th class="num">数量</th>
            <th>行备注</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(it, i) in billRows" :key="`${it.skuId}-${i}`">
            <td class="code">{{ it.skuId }}</td>
            <td>{{ it.skuTitle }}</td>
            <td class="num">{{ signed(it.qty) }}</td>
            <td>{{ it.remark || '—' }}</td>
          </tr>
        </tbody>
      </table>

      <div class="page-title drawer-section">来源说明</div>
      <div class="timeline">
        <div v-for="(tl, i) in detailTimeline" :key="i" class="tl-item done">
          <div class="tl-time">{{ tl.time }}</div>
          <div class="tl-label">{{ tl.label }}</div>
        </div>
      </div>

      <details class="json-debug">
        <summary class="hint json-debug-summary">原始数据（JSON 调试视图）</summary>
        <JsonViewer :value="detailRaw" title="JSON" empty-text="暂无数据" />
      </details>
    </template>
    <p v-else class="hint">未找到该单据</p>

    <template #footer="{ close }">
      <button class="btn btn-secondary" type="button" @click="close">关闭</button>
    </template>
  </DetailDrawer>
</template>

<style scoped>
.head-actions {
  display: flex;
  gap: 10px;
}
.req {
  color: var(--brick);
}
.input-narrow {
  max-width: 140px;
}
.input-date {
  max-width: 200px;
}
.empty-cell {
  text-align: center;
  padding: 24px;
}
.drawer-kv {
  margin-bottom: 18px;
}
.drawer-total {
  font-weight: 700;
}
.drawer-section {
  font-size: 13px;
  margin-bottom: 10px;
}
.drawer-table {
  margin-bottom: 18px;
}
.json-debug {
  margin-top: 18px;
}
.json-debug-summary {
  cursor: pointer;
}
</style>