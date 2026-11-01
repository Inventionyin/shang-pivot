/**
 * 库存管理 —— 对齐原型 .stitch/原型/admin/inventory.html。
 * 弹窗：dlg-inv-adjust（库存调整，增减录入）、dlg-inv-negative（调整后可售为负，拒绝）。
 * 预警档位一律走 utils/stock 的 stockTier / stockTierTone，不在本页重算阈值。
 */
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHead from '@/components/PageHead.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import ListPager from '@/components/ListPager.vue'
import FormDialog from '@/components/FormDialog.vue'
import NoticeDialog from '@/components/NoticeDialog.vue'
import { adjustStock, fetchStocks } from '@/api/inventory'
import { usePager } from '@/composables/usePager'
import { unwrapPage } from '@/utils/page'
import { usePermission } from '@/utils/permission'
import { stockTier, stockTierTone } from '@/utils/stock'

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

type TabKey = 'all' | 'low'
type StockRow = {
  skuId: number
  warehouseId: number
  sku: string
  name: string
  warehouse: string
  physical: number
  locked: number
  available: number
  threshold: number
  status: string
  tone: 'forest' | 'brick' | 'amber'
  highlight: boolean
}

const activeTab = ref<TabKey>('all')
const rows = ref<StockRow[]>([])
/** 预警档（低库存 + 关注）总条数，供 Tab 上的计数显示 */
const warnCount = ref(0)

/* ===== 库存调整（dlg-inv-adjust / D4-01）===== */
const adjustDialog = ref(false)
const adjusting = ref(false)
/** 从行内「调整」进入时的目标行；从页头「+ 库存调整」进入时为 null，需手填 SKU ID */
const adjustTarget = ref<StockRow | null>(null)
/** 页头进入时手填的 SKU ID 及其查询到的库存摘要 */
const skuInput = ref('')
const skuLookup = ref<StockRow | null>(null)
const skuLooking = ref(false)
const qty = ref('')
const warehouseId = ref(1)
/** V1 固定原因文案（PRD §8.12） */
const REASON = '后台手工调整'
const adjustErrors = ref<{ sku?: string; qty?: string }>({})
/** 后端拒绝原因，内联展示在弹窗里，不静默 */
const adjustServerMsg = ref('')

/* ===== 调整后可售为负（dlg-inv-negative / D4-02）===== */
const rejectDialog = ref(false)
const reject = ref({
  skuLabel: '',
  direction: '出库',
  qty: 0,
  afterPhysical: 0,
  locked: 0,
  afterAvailable: 0,
  msg: '',
})

/** 当前作为调整对象的库存摘要（行内进入用行数据，页头进入用查询结果） */
const current = computed<StockRow | null>(() => adjustTarget.value ?? skuLookup.value)
/** 只读摘要行：SKU · 商品名 */
const skuLabel = computed(() => {
  const c = current.value
  if (!c) return ''
  return `${c.sku} · ${c.name}`
})
const changeQty = computed(() => Number(qty.value))
/** 调整后：总库存 / 锁定占用 / 可售 */
const after = computed(() => {
  const c = current.value
  const delta = Number.isFinite(changeQty.value) ? changeQty.value : 0
  const physical = (c?.physical ?? 0) + delta
  const locked = c?.locked ?? 0
  return { physical, locked, available: physical - locked }
})
const wouldGoNegative = computed(() => after.value.available < 0)

function mapStockRow(r: Record<string, unknown>): StockRow {
  const available = Number(r.availableStock ?? r.available ?? 0)
  const threshold = Number(r.lowThreshold ?? r.threshold ?? 0)
  const physical = Number(r.physicalStock ?? r.physical ?? available)
  const locked = Number(r.lockedStock ?? r.locked ?? 0)
  const status = stockTier(available, threshold)
  const tone = stockTierTone(status)
  return {
    skuId: Number(r.skuId),
    warehouseId: Number(r.warehouseId ?? 1),
    sku: String(r.barCode ?? r.skuCode ?? r.skuId ?? '—'),
    name: String(r.skuTitle ?? r.name ?? r.title ?? '—'),
    warehouse: String(r.warehouseName ?? (r.warehouseId != null ? `仓库#${r.warehouseId}` : '—')),
    physical,
    locked,
    available,
    threshold,
    status,
    tone,
    highlight: status === '低库存' || status === '缺货',
  }
}

async function loadWarnCount() {
  try {
    const res = await fetchStocks({ warnOnly: true, page: 1, pageSize: 1 })
    warnCount.value = unwrapPage<Record<string, unknown>>(res.data)?.total ?? 0
  } catch {
    warnCount.value = 0
  }
}

async function load() {
  try {
    const res = await fetchStocks({
      // 「低库存」Tab 覆盖低库存 + 关注两档，后端下推过滤（勿在页内过滤，会把结果切碎）
      warnOnly: activeTab.value === 'low' ? true : undefined,
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
    rows.value = pageData.records.map(mapStockRow)
  } catch {
    rows.value = []
    total.value = 0
  }
}

function switchTab(tab: TabKey) {
  activeTab.value = tab
  resetPage()
  return load()
}

/* ===== 调整弹窗 ===== */
function openAdjust(row?: StockRow) {
  adjustTarget.value = row ?? null
  skuLookup.value = null
  skuInput.value = row ? String(row.skuId) : ''
  qty.value = ''
  warehouseId.value = row?.warehouseId ?? 1
  adjustErrors.value = {}
  adjustServerMsg.value = ''
  adjustDialog.value = true
}

/** 页头进入：按 SKU ID 拉当前库存，撑起只读摘要行 */
async function lookupSku() {
  const raw = skuInput.value.trim()
  skuLookup.value = null
  if (!raw || adjustTarget.value) return
  if (!/^\d+$/.test(raw)) {
    adjustErrors.value = { ...adjustErrors.value, sku: '请输入数字 SKU ID' }
    return
  }
  skuLooking.value = true
  try {
    const res = await fetchStocks({ skuId: Number(raw), page: 1, pageSize: 1 })
    const rec = unwrapPage<Record<string, unknown>>(res.data)?.records?.[0]
    if (rec) {
      skuLookup.value = mapStockRow(rec)
      warehouseId.value = skuLookup.value.warehouseId
      adjustErrors.value = { ...adjustErrors.value, sku: '' }
    } else {
      adjustErrors.value = { ...adjustErrors.value, sku: '未找到该 SKU 的库存记录' }
    }
  } catch {
    adjustErrors.value = { ...adjustErrors.value, sku: '未找到该 SKU 的库存记录' }
  } finally {
    skuLooking.value = false
  }
}

/** 「增加 / 减少」方向录入：正数入库、负数出库 */
function setDirection(sign: 1 | -1) {
  const abs = Math.abs(Number(qty.value))
  if (!qty.value.trim() || !Number.isFinite(abs)) {
    qty.value = sign === 1 ? '' : '-'
    return
  }
  qty.value = String(sign * abs)
}

async function submitAdjust() {
  if (adjusting.value) return
  adjustServerMsg.value = ''
  // 页头进入且未查询过：先按 SKU ID 拉一次库存，撑起只读摘要行
  if (!adjustTarget.value && !skuLookup.value && skuInput.value.trim()) {
    await lookupSku()
  }
  const errors: { sku?: string; qty?: string } = {}
  const target = current.value
  if (!target) {
    const rawId = skuInput.value.trim()
    if (!rawId) errors.sku = '请填写 SKU ID'
    else if (!/^\d+$/.test(rawId)) errors.sku = '请输入数字 SKU ID'
    else errors.sku = '未找到该 SKU 的库存记录'
  }
  const raw = qty.value.trim()
  if (!raw || raw === '-') errors.qty = '请填写调整数量'
  else if (!/^-?\d+$/.test(raw)) errors.qty = '调整数量为整数'
  else if (Number(raw) === 0) errors.qty = '调整数量不能为 0'
  adjustErrors.value = errors
  adjustServerMsg.value = ''
  if (Object.keys(errors).length || !target) return

  adjusting.value = true
  try {
    await adjustStock({
      skuId: target.skuId,
      warehouseId: warehouseId.value,
      changeQty: Number(raw),
      reason: REASON,
    })
    ElMessage.success('库存已调整')
    adjustDialog.value = false
    await Promise.all([load(), loadWarnCount()])
  } catch (e: unknown) {
    const msg = readErrMsg(e)
    adjustServerMsg.value = msg
    reject.value = {
      skuLabel: skuLabel.value,
      direction: Number(raw) < 0 ? '出库' : '入库',
      qty: Math.abs(Number(raw)),
      afterPhysical: after.value.physical,
      locked: after.value.locked,
      afterAvailable: after.value.available,
      msg,
    }
    rejectDialog.value = true
  } finally {
    adjusting.value = false
  }
}

function readErrMsg(e: unknown): string {
  if (e && typeof e === 'object') {
    const direct = (e as { msg?: unknown }).msg
    if (typeof direct === 'string' && direct) return direct
    const nested = (e as { response?: { data?: { msg?: unknown } } }).response?.data?.msg
    if (typeof nested === 'string' && nested) return nested
  }
  return '调整失败，请稍后重试'
}

onMounted(() => {
  void load()
  void loadWarnCount()
})
</script>

<template>
  <div class="list-page">
    <PageHead
      title="库存管理"
      sub="总库存 = 仓库实有件数 · 锁定占用 = 已下单未出库 · 可售 = 总库存 − 锁定；预警口径：低库存 = 可售 ≤ 阈值，关注 = 阈值 < 可售 ≤ 阈值 × 1.5"
    >
      <template #actions>
        <button
          v-if="hasPermission('stock:adjust')"
          class="btn btn-primary"
          type="button"
          @click="openAdjust()"
        >
          + 库存调整
        </button>
      </template>
    </PageHead>

    <div class="card card-pad list-card">
      <div class="tabs">
        <div class="tab" :class="{ active: activeTab === 'all' }" @click="switchTab('all')">
          全部 <span class="c">{{ total }}</span>
        </div>
        <div
          class="tab tab-warn"
          :class="{ active: activeTab === 'low' }"
          @click="switchTab('low')"
        >
          低库存 / 关注 <span class="c">{{ activeTab === 'low' ? total : warnCount }}</span>
        </div>
      </div>

      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th class="code">SKU</th>
              <th class="col-name">商品</th>
              <th>仓库</th>
              <th class="num" title="仓库实有件数">总库存</th>
              <th class="num" title="已下单占用、暂不可再卖">锁定占用</th>
              <th class="num" title="总库存 − 锁定">可售</th>
              <th class="num">阈值</th>
              <th>状态</th>
              <th class="col-op">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!rows.length">
              <td colspan="9" class="hint empty-cell">暂无库存数据</td>
            </tr>
            <tr
              v-for="row in rows"
              :key="`${row.skuId}-${row.warehouseId}`"
              :class="{ 'row-warn': row.highlight }"
            >
              <td class="code">{{ row.sku }}</td>
              <td>{{ row.name }}</td>
              <td>{{ row.warehouse }}</td>
              <td class="num">{{ row.physical }}</td>
              <td class="num">{{ row.locked }}</td>
              <td class="num">{{ row.available }}</td>
              <td class="num">{{ row.threshold }}</td>
              <td><StatusBadge :text="row.status" :tone="row.tone" /></td>
              <td class="col-op">
                <button
                  v-if="hasPermission('stock:adjust')"
                  class="btn btn-ghost btn-sm"
                  type="button"
                  @click="openAdjust(row)"
                >
                  调整
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

    <!-- 库存调整（dlg-inv-adjust / D4-01） -->
    <FormDialog
      v-model="adjustDialog"
      title="库存调整"
      icon="info"
      icon-text="±"
      width="is-md"
      cancel-text="取消"
      confirm-text="确认调整"
      :loading="adjusting"
      @submit="submitAdjust"
    >
      <p v-if="adjustServerMsg" class="error-text">{{ adjustServerMsg }}</p>
      <div class="field">
        <label class="label">SKU</label>
        <!-- 原型：10001 · iPhone 15 Pro 128G · 128GB / 原色钛金属（只读） -->
        <input v-if="adjustTarget" class="input" :value="skuLabel" readonly />
        <input
          v-else
          v-model="skuInput"
          class="input"
          inputmode="numeric"
          placeholder="请输入 SKU ID"
          @keyup.enter="lookupSku"
          @blur="lookupSku"
        />
        <span v-if="skuLooking" class="hint">查询中…</span>
        <span v-else-if="skuLabel" class="hint">{{ skuLabel }}</span>
        <span v-else class="hint">行内「调整」可自动带入 SKU；此处手填 SKU ID 后回车查询</span>
        <span v-if="adjustErrors.sku" class="error-text">{{ adjustErrors.sku }}</span>
      </div>

      <div v-if="current" class="kv-line">
        <span>当前</span>
        <span>总库存 <b>{{ current.physical }}</b></span>
        <span>锁定占用 <b>{{ current.locked }}</b></span>
        <span>可售 <b>{{ current.available }}</b></span>
      </div>

      <div class="form-grid">
        <div class="field">
          <label class="label">仓库</label>
          <select v-model.number="warehouseId" class="select">
            <option :value="current?.warehouseId ?? 1">
              {{ current?.warehouse ?? '默认仓' }}
            </option>
          </select>
          <span class="hint">V1 仅一个仓库，默认「默认仓」</span>
        </div>
        <div class="field">
          <label class="label">调整数量<span class="req">*</span></label>
          <div class="qty-row">
            <button
              class="btn btn-secondary btn-sm"
              :class="{ 'is-on': qty.trim().startsWith('-') }"
              type="button"
              @click="setDirection(-1)"
            >
              减少
            </button>
            <button
              class="btn btn-secondary btn-sm"
              :class="{ 'is-on': qty.trim() !== '' && !qty.trim().startsWith('-') }"
              type="button"
              @click="setDirection(1)"
            >
              增加
            </button>
            <input v-model="qty" class="input qty-input" inputmode="numeric" placeholder="-101" />
          </div>
          <span class="hint">正数入库、负数出库，不能为 0</span>
          <span v-if="adjustErrors.qty" class="error-text">{{ adjustErrors.qty }}</span>
        </div>
      </div>

      <div class="field">
        <label class="label">调整原因</label>
        <input class="input" :value="REASON" readonly />
        <span class="hint">V1 后台调整固定写入该原因，随流水一并记录</span>
      </div>

      <div v-if="current" class="alert" :class="wouldGoNegative ? 'alert-warn' : 'alert-info'">
        调整后：总库存 <b>{{ after.physical }}</b> · 锁定占用 <b>{{ after.locked }}</b> · 可售
        <b>{{ after.available }}</b>。<template v-if="wouldGoNegative">
          本次调整会使可售库存为负，确认后将被拒绝。</template
        >
      </div>
    </FormDialog>

    <!-- 调整后可售为负（dlg-inv-negative / D4-02） -->
    <NoticeDialog
      v-model="rejectDialog"
      title="无法调整"
      icon="warn"
      width="is-sm"
      confirm-text="知道了"
    >
      <p>
        SKU <b class="code">{{ reject.skuLabel }}</b> {{ reject.direction }}
        <b>{{ reject.qty }}</b> 件后总库存为 <b>{{ reject.afterPhysical }}</b>，小于锁定占用
        <b>{{ reject.locked }}</b>，可售库存为 <b class="code">{{ reject.afterAvailable }}</b>。
      </p>
      <p class="hint">
        库存不变式：总库存 ≥ 锁定占用 ≥ 0，即 可售 = 总库存 − 锁定 ≥ 0。
        请减少出库数量，或先补货 / 等订单出库释放锁定后再调整。
      </p>
      <p v-if="reject.msg" class="hint">{{ reject.msg }}</p>
    </NoticeDialog>
  </div>
</template>

<style scoped>
/* 原型 inventory.html 内联样式的类化版本（不写行内 style） */
.tab-warn {
  color: var(--brick);
}
.col-name {
  min-width: 220px;
}
.empty-cell {
  text-align: center;
  padding: 24px;
}
/* 原型：低库存 / 缺货行底色 var(--cream) */
.row-warn {
  background: var(--cream);
}
.qty-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.qty-input {
  flex: 1;
  min-width: 0;
}
.qty-row .btn.is-on {
  border-color: var(--coral);
  color: var(--coral);
}
.kv-line {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  padding: 9px 12px;
  margin-bottom: 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--sand);
  font-size: 12.5px;
  color: var(--stone);
}
.kv-line b {
  color: var(--ink);
  font-family: var(--font-mono);
}
</style>