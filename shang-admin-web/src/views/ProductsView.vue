<script setup lang="ts">
/**
 * 商品列表 —— 对齐原型 .stitch/原型/admin/products.html。
 * 弹窗：dlg-p-on-fail（上架校验失败，逐条列原因）、dlg-p-off-batch（批量下架）、
 *       dlg-p-del-draft（删除草稿）、dlg-p-del-reject（仅草稿可删，拒绝）。
 * 状态筛选为带条数的 Tab（全部 / 上架 / 下架 / 草稿），条数取后端 total，不写死。
 */
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHead from '@/components/PageHead.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import ListPager from '@/components/ListPager.vue'
import FormDialog from '@/components/FormDialog.vue'
import NoticeDialog from '@/components/NoticeDialog.vue'
import { fetchCategoryTree } from '@/api/categories'
import { batchOffShelf, deleteProduct, fetchProducts, offShelf, onShelf } from '@/api/products'
import { fetchStocks } from '@/api/inventory'
import { usePager } from '@/composables/usePager'
import { resolveMediaUrl } from '@/utils/media'
import { unwrapList, unwrapPage } from '@/utils/page'
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

type TabKey = 'ALL' | 'ON' | 'OFF' | 'DRAFT'
type StatusTone = 'forest' | 'brick' | 'amber'

/** 原型 tabs：全部 / 上架 / 下架 / 草稿（条数来自后端 total） */
const TABS: { key: TabKey; label: string }[] = [
  { key: 'ALL', label: '全部' },
  { key: 'ON', label: '上架' },
  { key: 'OFF', label: '下架' },
  { key: 'DRAFT', label: '草稿' },
]

interface ProductRow {
  id: string
  name: string
  category: string
  priceRange: string
  skuCount: number
  stock: number
  status: string
  rawStatus: string
  statusTone: StatusTone
  thumb: string
  /** 主图张数，供上架校验逐条列原因 */
  picCount: number
  /** 已填售价的最高价，0 表示没有已填售价的 SKU */
  maxPrice: number
}

interface CatOpt {
  id: string
  name: string
}

const keyword = ref('')
const category = ref('')
const activeTab = ref<TabKey>('ALL')
const rows = ref<ProductRow[]>([])
const categories = ref<CatOpt[]>([])
const selected = ref<Set<string>>(new Set())
const counts = ref<Record<TabKey, number>>({ ALL: 0, ON: 0, OFF: 0, DRAFT: 0 })
/** SKU 总数（库存记录数），仅用于页头副标题 */
const skuTotal = ref(0)

const pageSub = computed(
  () =>
    `SPU ${counts.value.ALL} 个（在售 ${counts.value.ON}）` +
    (skuTotal.value ? ` · SKU ${skuTotal.value} 个` : '') +
    ' · 可售库存 = 总库存 − 锁定占用',
)

/* ===== 上架校验失败（dlg-p-on-fail / D2-01）===== */
const onFailDialog = ref(false)
const onFail = ref({
  name: '',
  noPic: false,
  noPrice: false,
  skuCount: 0,
  msg: '',
})
/** 上架请求进行中，防重复点击 */
const onShelfId = ref('')

/* ===== 下架确认（dlg-p-off-batch / D2-02）===== */
const offDialog = ref(false)
const offTargets = ref<ProductRow[]>([])
const offSaving = ref(false)

/* ===== 删除草稿（dlg-p-del-draft / D2-03）===== */
const delDialog = ref(false)
const delTarget = ref<ProductRow | null>(null)
const delSaving = ref(false)

/* ===== 仅草稿可删（dlg-p-del-reject / D2-04）===== */
const delRejectDialog = ref(false)
const delReject = ref({ name: '', statusLabel: '上架', statusTone: 'forest' as StatusTone, msg: '' })

function readErrMsg(e: unknown): string {
  if (e && typeof e === 'object') {
    const direct = (e as { msg?: unknown }).msg
    if (typeof direct === 'string' && direct) return direct
    const nested = (e as { response?: { data?: { msg?: unknown } } }).response?.data?.msg
    if (typeof nested === 'string' && nested) return nested
  }
  return ''
}

function flattenCats(nodes: Record<string, unknown>[], prefix = ''): CatOpt[] {
  const out: CatOpt[] = []
  for (const n of nodes) {
    const name = `${prefix}${String(n.name ?? '')}`
    out.push({ id: String(n.id ?? ''), name })
    const children = n.children
    if (Array.isArray(children) && children.length) {
      out.push(...flattenCats(children as Record<string, unknown>[], `${name} / `))
    }
  }
  return out
}

async function loadCats() {
  try {
    const res = await fetchCategoryTree()
    categories.value = flattenCats(unwrapList<Record<string, unknown>>(res.data))
  } catch {
    categories.value = []
  }
}

function totalOf(res: { data: unknown }): number {
  return unwrapPage<Record<string, unknown>>(res.data)?.total ?? 0
}

/** Tab 条数：按状态分别取后端 total（keyword / 类目筛选同步作用其上） */
async function loadCounts() {
  const base = {
    keyword: keyword.value || undefined,
    categoryId: category.value || undefined,
    page: 1,
    pageSize: 1,
  }
  const [all, on, off, draft] = await Promise.all([
    fetchProducts(base).then(totalOf).catch(() => 0),
    fetchProducts({ ...base, status: 'ON' }).then(totalOf).catch(() => 0),
    fetchProducts({ ...base, status: 'OFF' }).then(totalOf).catch(() => 0),
    fetchProducts({ ...base, status: 'DRAFT' }).then(totalOf).catch(() => 0),
  ])
  counts.value = { ALL: all, ON: on, OFF: off, DRAFT: draft }
}

/** 页头副标题里的 SKU 总数（与库存管理「全部」条数同源） */
async function loadSkuTotal() {
  try {
    const res = await fetchStocks({ page: 1, pageSize: 1 })
    skuTotal.value = totalOf(res)
  } catch {
    skuTotal.value = 0
  }
}

async function load() {
  try {
    const res = await fetchProducts({
      keyword: keyword.value || undefined,
      status: activeTab.value === 'ALL' ? undefined : activeTab.value,
      categoryId: category.value || undefined,
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
    rows.value = pageData.records.map((spu, idx) => {
      const st = String(spu.status || '')
      const statusLabel =
        st === 'ON' ? '上架' : st === 'OFF' ? '下架' : st === 'DRAFT' ? '草稿' : st || '—'
      const tone = (st === 'ON' ? 'forest' : st === 'OFF' ? 'brick' : 'amber') as StatusTone
      const pics = Array.isArray(spu.pics) ? (spu.pics as unknown[]) : []
      const thumb = pics[0]
        ? `url(${JSON.stringify(resolveMediaUrl(String(pics[0])))}) center/cover`
        : 'linear-gradient(135deg,#FEBB4A,#FF8A5B)'
      return {
        id: String(spu.id ?? idx),
        name: String(spu.name ?? ''),
        category: String(spu.categoryName ?? '—'),
        priceRange: String(spu.priceRange ?? '—'),
        skuCount: Number(spu.skuCount ?? 0),
        stock: Number(spu.stock ?? 0),
        status: statusLabel,
        rawStatus: st,
        statusTone: tone,
        thumb,
        picCount: pics.length,
        maxPrice: Number(spu.maxPrice ?? 0),
      }
    })
    selected.value = new Set()
  } catch {
    rows.value = []
    total.value = 0
  }
}

async function refresh() {
  await load()
  await loadCounts()
}

function search() {
  resetPage()
  return refresh()
}

function switchTab(tab: TabKey) {
  if (activeTab.value === tab) return
  activeTab.value = tab
  resetPage()
  return load()
}

function toggleSelect(id: string) {
  const next = new Set(selected.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selected.value = next
}

/** 上架校验失败逐条列原因：主图 / 已填售价的 SKU */
function openOnShelfFail(row: ProductRow, msg = '') {
  onFail.value = {
    name: row.name,
    noPic: row.picCount === 0,
    noPrice: !(row.maxPrice > 0),
    skuCount: row.skuCount,
    msg,
  }
  onFailDialog.value = true
}

async function tryOnShelf(row: ProductRow) {
  const noPic = row.picCount === 0
  const noPrice = !(row.maxPrice > 0)
  if (noPic || noPrice) {
    openOnShelfFail(row)
    return
  }
  onShelfId.value = row.id
  try {
    await onShelf(row.id)
    ElMessage.success('已上架')
    await refresh()
  } catch (e: unknown) {
    // 后端同样拒绝时把原因并进同一张清单，不静默
    openOnShelfFail(row, readErrMsg(e))
  } finally {
    onShelfId.value = ''
  }
}

/* ===== 下架 / 批量下架 ===== */
function openOff(rowsToOff: ProductRow[]) {
  offTargets.value = rowsToOff
  offDialog.value = true
}

async function toggleShelf(row: ProductRow) {
  if (row.rawStatus === 'ON') {
    openOff([row])
    return
  }
  await tryOnShelf(row)
}

async function confirmOff() {
  const targets = offTargets.value
  if (!targets.length) return
  offSaving.value = true
  try {
    if (targets.length === 1) {
      await offShelf(targets[0]!.id)
      ElMessage.success('已下架')
    } else {
      const res = await batchOffShelf(targets.map((r) => r.id))
      const n = Number(res.data ?? 0)
      ElMessage.success(n > 0 ? `已下架 ${n} 个商品` : '没有可下架的上架商品')
    }
    offDialog.value = false
    await refresh()
  } catch {
    /* 拦截器已提示，弹窗保留可重试 */
  } finally {
    offSaving.value = false
  }
}

function batchOff() {
  if (!selected.value.size) {
    ElMessage.warning('请先勾选商品')
    return
  }
  openOff(rows.value.filter((r) => selected.value.has(r.id)))
}

/* ===== 删除草稿 ===== */
function openDeleteDraft(row: ProductRow) {
  if (row.rawStatus !== 'DRAFT') return
  delTarget.value = row
  delDialog.value = true
}

async function confirmDeleteDraft() {
  const row = delTarget.value
  if (!row) return
  delSaving.value = true
  try {
    await deleteProduct(row.id)
    ElMessage.success('已删除')
    delDialog.value = false
    await refresh()
  } catch (e: unknown) {
    delDialog.value = false
    delReject.value = {
      name: row.name,
      statusLabel: row.status,
      statusTone: row.statusTone,
      msg: readErrMsg(e),
    }
    delRejectDialog.value = true
  } finally {
    delSaving.value = false
  }
}

onMounted(async () => {
  await loadCats()
  await Promise.all([load(), loadCounts(), loadSkuTotal()])
})
</script>

<template>
  <div class="list-page">
    <PageHead title="商品列表" :sub="pageSub">
      <template #actions>
        <RouterLink
          v-if="hasPermission('product:add')"
          class="btn btn-primary"
          to="/products/edit"
        >
          + 新增商品
        </RouterLink>
      </template>
    </PageHead>

    <div class="card card-pad list-card">
      <div class="tabs">
        <div
          v-for="t in TABS"
          :key="t.key"
          class="tab"
          :class="{ active: activeTab === t.key }"
          @click="switchTab(t.key)"
        >
          {{ t.label }} <span class="c">{{ counts[t.key] }}</span>
        </div>
      </div>

      <div class="toolbar">
        <input
          v-model="keyword"
          class="input"
          placeholder="商品名称 / 编码"
          @keyup.enter="search"
        />
        <select v-model="category" class="select">
          <option value="">全部类目</option>
          <option v-for="c in categories" :key="c.id" :value="c.id">{{ c.name }}</option>
        </select>
        <button class="btn btn-primary btn-sm" type="button" @click="search">查询</button>
        <div class="toolbar-actions">
          <button
            v-if="hasPermission('product:off')"
            class="btn btn-secondary btn-sm"
            type="button"
            @click="batchOff"
          >
            批量下架
          </button>
        </div>
      </div>

      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th class="col-check"></th>
              <th class="col-thumb">商品图</th>
              <th class="col-name">商品名称</th>
              <th>类目</th>
              <th class="num">价格区间</th>
              <th class="num">SKU 数</th>
              <th class="num">可售库存</th>
              <th>状态</th>
              <th class="col-op">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!rows.length">
              <td colspan="9" class="hint empty-cell">暂无商品</td>
            </tr>
            <tr v-for="row in rows" :key="row.id">
              <td>
                <input
                  type="checkbox"
                  :checked="selected.has(row.id)"
                  @change="toggleSelect(row.id)"
                />
              </td>
              <td>
                <div class="thumb" :style="{ background: row.thumb }"></div>
              </td>
              <td>{{ row.name }}</td>
              <td>{{ row.category }}</td>
              <td class="num">{{ row.priceRange }}</td>
              <td class="num">{{ row.skuCount }}</td>
              <td class="num">{{ row.stock }}</td>
              <td><StatusBadge :text="row.status" :tone="row.statusTone" /></td>
              <td class="col-op">
                <RouterLink
                  v-if="hasPermission('product:edit')"
                  class="btn btn-ghost btn-sm"
                  :to="`/products/edit/${row.id}`"
                >
                  编辑
                </RouterLink>
                <button
                  v-if="hasPermission(row.rawStatus === 'ON' ? 'product:off' : 'product:on')"
                  class="btn btn-ghost btn-sm"
                  type="button"
                  :disabled="onShelfId === row.id"
                  @click="toggleShelf(row)"
                >
                  {{ row.rawStatus === 'ON' ? '下架' : '上架' }}
                </button>
                <button
                  v-if="row.rawStatus === 'DRAFT' && hasPermission('product:edit')"
                  class="btn btn-ghost btn-sm"
                  type="button"
                  @click="openDeleteDraft(row)"
                >
                  删除
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

    <!-- 上架校验失败（dlg-p-on-fail / D2-01） -->
    <NoticeDialog
      v-model="onFailDialog"
      title="无法上架"
      icon="warn"
      width="is-md"
      confirm-text="去补充"
    >
      <p>商品「{{ onFail.name }}」不满足上架条件，请修正后重试：</p>
      <ul class="impact">
        <li v-if="onFail.noPic">至少需要 <b>1</b> 张主图（当前 0 张）</li>
        <li v-if="onFail.noPrice">
          至少需要 <b>1</b> 个已填售价的 SKU（当前 {{ onFail.skuCount }} 个 SKU 未填售价）
        </li>
        <li v-if="onFail.msg">{{ onFail.msg }}</li>
      </ul>
      <div class="alert alert-info impact-alert">
        校验规则：主图非空、至少 1 个启用 SKU、售价 &gt; 0。库存为 0 不阻止上架；
        校验不通过时商品停留在<b>草稿</b>状态，已填内容不丢失。
      </div>
    </NoticeDialog>

    <!-- 批量下架 / 行内下架（dlg-p-off-batch / D2-02） -->
    <FormDialog
      v-model="offDialog"
      title="批量下架"
      icon="danger"
      width="is-md"
      cancel-text="取消"
      :confirm-text="`确认下架（${offTargets.length}）`"
      :loading="offSaving"
      @submit="confirmOff"
    >
      <p>确认下架已勾选的 <b>{{ offTargets.length }}</b> 个商品？</p>
      <ul class="impact">
        <li v-for="row in offTargets" :key="row.id">
          <b>{{ row.name }}</b> {{ row.category }} · {{ row.skuCount }} 个 SKU
        </li>
      </ul>
      <div class="alert alert-warn impact-alert">
        下架后顾客端<b>立即不可见、不可下单</b>；已生成订单不受影响，库存与 SKU 数据保留，可随时重新上架。
      </div>
    </FormDialog>

    <!-- 删除草稿（dlg-p-del-draft / D2-03） -->
    <FormDialog
      v-model="delDialog"
      title="删除商品"
      icon="danger"
      width="is-sm"
      cancel-text="取消"
      confirm-text="确认删除"
      :loading="delSaving"
      @submit="confirmDeleteDraft"
    >
      <p>确认删除草稿「{{ delTarget?.name }}」？</p>
      <p class="hint">
        将一并删除该 SPU 下的 <b>{{ delTarget?.skuCount ?? 0 }}</b> 个 SKU
        及其库存记录，删除后不可恢复。
      </p>
    </FormDialog>

    <!-- 仅草稿可删（dlg-p-del-reject / D2-04） -->
    <NoticeDialog
      v-model="delRejectDialog"
      title="仅草稿可删除"
      icon="warn"
      width="is-sm"
      confirm-text="知道了"
    >
      <p>
        商品 <b>{{ delReject.name }}</b> 当前为
        <StatusBadge :text="delReject.statusLabel" :tone="delReject.statusTone" /> 状态，不能删除。
      </p>
      <p class="hint">
        删除按钮只在「草稿」行显示；对非草稿调用删除接口时，后端同样以 409
        拒绝，提示「仅草稿可删」。
      </p>
      <p v-if="delReject.msg" class="hint">{{ delReject.msg }}</p>
    </NoticeDialog>
  </div>
</template>

<style scoped>
/* 原型 products.html 内联样式的类化版本（不写行内 style 布局） */
.col-check {
  width: 28px;
}
.col-thumb {
  width: 64px;
}
.col-name {
  min-width: 220px;
}
.empty-cell {
  text-align: center;
  padding: 24px;
}
.impact-alert {
  margin-top: 12px;
}
</style>