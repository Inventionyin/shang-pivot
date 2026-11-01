<script setup lang="ts">
/**
 * 类目与品牌 —— 对齐原型 .stitch/原型/admin/categories.html。
 * 弹窗：dlg-cat-form（新增/编辑类目）、dlg-cat-del（删除确认）、
 *       dlg-cat-reject（被引用拒绝）、dlg-brand-form、dlg-attr-form。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHead from '@/components/PageHead.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import FormDialog from '@/components/FormDialog.vue'
import NoticeDialog from '@/components/NoticeDialog.vue'
import OssImageUpload from '@/components/OssImageUpload.vue'
import {
  deleteAttribute,
  deleteBrand,
  deleteCategory,
  fetchAttributes,
  fetchBrands,
  fetchCategoryTree,
  saveAttribute,
  saveBrand,
  saveCategory,
} from '@/api/categories'
import { fetchProducts } from '@/api/products'
import { unwrapList, unwrapPage } from '@/utils/page'
import { resolveMediaUrl } from '@/utils/media'

type CatNode = {
  id: string
  parentId: string
  name: string
  level: number
  sort: number
  status: string
  children: CatNode[]
}

type CatFlat = {
  id: string
  parentId: string
  name: string
  level: number
  childIds: string[]
}

type TreeRow = { node: CatNode; depth: number }

type BrandRow = {
  id: string
  name: string
  logo: string
  sort: number
  status: string
  spuCount: number
  categoryId: string
}

type AttrRow = {
  id: string
  name: string
  type: string
  typeLabel: string
  options: string[]
  sort: number
  categoryId: string
}

type CatImpact = { products: number; attrs: number; attrNames: string[]; children: number }

const categories = ref<CatNode[]>([])
const brands = ref<BrandRow[]>([])
const attributes = ref<AttrRow[]>([])
const selectedCategoryId = ref<string>('')
const selectedCategoryName = ref('全部类目')
const expanded = ref<Set<string>>(new Set())
const productCounts = ref<Record<string, number>>({})

/* ===== 类目 ===== */
const catDialog = ref(false)
const catEditingId = ref<string | null>(null)
const catSaving = ref(false)
const catFormError = ref('')
const catForm = ref<{ name: string; parentId: string; sort: number | string; status: string }>({
  name: '',
  parentId: '0',
  sort: 0,
  status: 'ENABLED',
})
const catErrors = ref<{ name?: string; sort?: string }>({})

const catDelDialog = ref(false)
const catDelTarget = ref<CatNode | null>(null)
const catDeleting = ref(false)
const catDelImpact = ref<CatImpact>({ products: 0, attrs: 0, attrNames: [], children: 0 })

const catRejectDialog = ref(false)
const catReject = ref({ name: '', products: 0, attrs: 0, attrNames: [] as string[], msg: '' })

/* ===== 品牌 ===== */
const brandDialog = ref(false)
const brandEditingId = ref<string | null>(null)
const brandSaving = ref(false)
const brandFormError = ref('')
const brandForm = ref<{
  name: string
  categoryId: string
  logo: string
  sort: number | string
  status: string
}>({ name: '', categoryId: '', logo: '', sort: 0, status: 'ENABLED' })
const brandErrors = ref<{ name?: string; categoryId?: string }>({})

const brandDelDialog = ref(false)
const brandDelTarget = ref<BrandRow | null>(null)
const brandDeleting = ref(false)

/* ===== 属性 ===== */
const attrDialog = ref(false)
const attrEditingId = ref<string | null>(null)
const attrSaving = ref(false)
const attrFormError = ref('')
const attrForm = ref<{
  categoryId: string
  name: string
  type: string
  options: string
  sort: number | string
}>({ categoryId: '', name: '', type: 'SPEC', options: '', sort: 0 })
const attrErrors = ref<{ categoryId?: string; name?: string; options?: string }>({})

const attrDelDialog = ref(false)
const attrDelTarget = ref<AttrRow | null>(null)
const attrDeleting = ref(false)
/** 删除属性提示里的类型词（规格 / 参数） */
const attrDelTypeLabel = computed(() => attrDelTarget.value?.typeLabel ?? '规格')

/* ===== 品牌 / 属性删除被拒（后端 msg）===== */
const delRejectDialog = ref(false)
const delRejectTitle = ref('')
const delRejectMsg = ref('')

function toCatNodes(list: Record<string, unknown>[]): CatNode[] {
  return list.map((c) => ({
    id: String(c.id ?? ''),
    parentId: String(c.parentId ?? '0'),
    name: String(c.name ?? ''),
    level: Number(c.level ?? 1),
    sort: Number(c.sort ?? 0),
    status: String(c.status ?? 'ENABLED'),
    children: Array.isArray(c.children)
      ? toCatNodes(c.children as Record<string, unknown>[])
      : [],
  }))
}

function flatten(nodes: CatNode[]): CatFlat[] {
  const out: CatFlat[] = []
  const walk = (list: CatNode[]) => {
    for (const n of list) {
      out.push({
        id: n.id,
        parentId: n.parentId,
        name: n.name,
        level: n.level,
        childIds: n.children.map((c) => c.id),
      })
      walk(n.children)
    }
  }
  walk(nodes)
  return out
}

const flatCats = computed(() => flatten(categories.value))
/** 顶级类目数（原型 hint「顶级类目共 N 个」） */
const topCount = computed(() => categories.value.length)
const catNameById = computed(() => new Map(flatCats.value.map((c) => [c.id, c.name])))
const pageSub = computed(
  () => `点击左侧类目，右侧展示该类目下关联品牌与属性 · 共 ${topCount.value} 个一级类目`,
)

const visibleRows = computed<TreeRow[]>(() => {
  const rows: TreeRow[] = []
  const walk = (nodes: CatNode[], depth: number) => {
    for (const n of nodes) {
      rows.push({ node: n, depth })
      if (n.children.length && expanded.value.has(n.id)) walk(n.children, depth + 1)
    }
  }
  walk(categories.value, 0)
  return rows
})

/** 编辑时不能把类目挂到自己或自己的下级下 */
const catParentOptions = computed<CatFlat[]>(() => {
  const id = catEditingId.value
  if (!id) return flatCats.value
  const banned = new Set<string>([id])
  const queue = [...(flatCats.value.find((c) => c.id === id)?.childIds ?? [])]
  while (queue.length) {
    const cur = queue.shift() as string
    if (banned.has(cur)) continue
    banned.add(cur)
    queue.push(...(flatCats.value.find((c) => c.id === cur)?.childIds ?? []))
  }
  return flatCats.value.filter((c) => !banned.has(c.id))
})

const catRejectNames = computed(() =>
  catReject.value.attrNames.length ? `（${catReject.value.attrNames.join('、')}）` : '',
)
/** 子类目等非引用类拒绝：把后端 msg 一并显示，避免屏幕上只写「0 个商品、0 个属性」 */
const catRejectExtra = computed(() =>
  catReject.value.products + catReject.value.attrs === 0 ? catReject.value.msg : '',
)

function catName(id: string): string {
  return catNameById.value.get(id) ?? '—'
}

function productCount(id: string): number {
  return productCounts.value[id] ?? 0
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

async function loadTree() {
  try {
    const res = await fetchCategoryTree()
    categories.value = toCatNodes(unwrapList<Record<string, unknown>>(res.data))
    const cur = flatCats.value.find((c) => c.id === selectedCategoryId.value)
    if (cur) selectedCategoryName.value = cur.name
  } catch {
    categories.value = []
  }
}

/** 类目行「N 商品」：原型逐行展示，取每行类目下的 SPU 总数 */
async function loadProductCounts() {
  const entries = await Promise.all(
    flatCats.value.map(async (c) => {
      try {
        const res = await fetchProducts({ categoryId: c.id, page: 1, pageSize: 1 })
        return [c.id, unwrapPage<Record<string, unknown>>(res.data)?.total ?? 0] as const
      } catch {
        return [c.id, 0] as const
      }
    }),
  )
  productCounts.value = Object.fromEntries(entries)
}

async function loadRight() {
  const catId = selectedCategoryId.value ? Number(selectedCategoryId.value) : undefined
  const params = catId != null && !Number.isNaN(catId) ? { categoryId: catId } : {}
  try {
    const [brandRes, attrRes] = await Promise.all([
      fetchBrands({ page: 1, pageSize: 200, ...params }),
      fetchAttributes(params),
    ])
    const brandPage = unwrapPage<Record<string, unknown>>(brandRes.data)
    brands.value = (brandPage?.records ?? []).map((b) => ({
      id: String(b.id ?? ''),
      name: String(b.name ?? ''),
      logo: String(b.logo ?? ''),
      sort: Number(b.sort ?? 0),
      status: String(b.status ?? 'ENABLED'),
      spuCount: Number(b.spuCount ?? 0),
      categoryId: String(b.categoryId ?? ''),
    }))
    attributes.value = unwrapList<Record<string, unknown>>(attrRes.data).map((a) => {
      const type = String(a.type ?? 'SPEC')
      return {
        id: String(a.id ?? ''),
        name: String(a.name ?? ''),
        type,
        typeLabel: type === 'PARAM' ? '参数' : '规格',
        options: Array.isArray(a.options) ? (a.options as unknown[]).map((v) => String(v)) : [],
        sort: Number(a.sort ?? 0),
        categoryId: String(a.categoryId ?? ''),
      }
    })
  } catch (e) {
    console.error('load brands/attrs failed', e)
    brands.value = []
    attributes.value = []
  }
}

async function load() {
  await loadTree()
  await Promise.all([loadProductCounts(), loadRight()])
}

async function selectCategory(cat: CatNode | null) {
  if (!cat) {
    selectedCategoryId.value = ''
    selectedCategoryName.value = '全部类目'
  } else {
    selectedCategoryId.value = cat.id
    selectedCategoryName.value = cat.name
  }
  await loadRight()
}

function toggleExpand(id: string) {
  const next = new Set(expanded.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expanded.value = next
}

/* ===== 类目：新增 / 编辑 ===== */
function openCatCreate(parentId = '0') {
  catEditingId.value = null
  catFormError.value = ''
  catErrors.value = {}
  catForm.value = { name: '', parentId, sort: 0, status: 'ENABLED' }
  catDialog.value = true
}

function openCatEdit(cat: CatNode) {
  catEditingId.value = cat.id
  catFormError.value = ''
  catErrors.value = {}
  catForm.value = {
    name: cat.name,
    parentId: cat.parentId || '0',
    sort: cat.sort,
    status: cat.status || 'ENABLED',
  }
  catDialog.value = true
}

async function submitCategory() {
  const errors: { name?: string; sort?: string } = {}
  const name = catForm.value.name.trim()
  if (!name) errors.name = '请填写类目名称'
  else if (name.length > 30) errors.name = '类目名称为 1～30 字符'
  else if (
    flatCats.value.some(
      (c) => c.id !== catEditingId.value && c.parentId === catForm.value.parentId && c.name === name,
    )
  ) {
    errors.name = '同级已存在同名类目'
  }
  const sort = Number(catForm.value.sort)
  if (catForm.value.sort === '' || Number.isNaN(sort)) errors.sort = '请填写排序'
  else if (!Number.isInteger(sort) || sort < 0 || sort > 999) errors.sort = '排序为 0～999 的整数'
  catErrors.value = errors
  catFormError.value = ''
  if (Object.keys(errors).length) return

  catSaving.value = true
  try {
    const parentId = Number(catForm.value.parentId || 0)
    const parent = flatCats.value.find((c) => c.id === String(parentId))
    await saveCategory(
      {
        name,
        parentId,
        level: parentId === 0 ? 1 : (parent?.level ?? 1) + 1,
        sort,
        status: catForm.value.status,
      },
      catEditingId.value ?? undefined,
    )
    ElMessage.success(catEditingId.value ? '已保存' : '已创建')
    catDialog.value = false
    if (parentId !== 0) {
      const next = new Set(expanded.value)
      next.add(String(parentId))
      expanded.value = next
    }
    await loadTree()
    await Promise.all([loadProductCounts(), loadRight()])
  } catch (e: unknown) {
    catFormError.value = readErrMsg(e) || '保存失败，请稍后重试'
  } finally {
    catSaving.value = false
  }
}

/* ===== 类目：删除确认 / 被引用拒绝 ===== */
async function loadCatImpact(cat: CatNode): Promise<CatImpact> {
  const [products, attrs] = await Promise.all([
    fetchProducts({ categoryId: cat.id, page: 1, pageSize: 1 })
      .then((res) => unwrapPage<Record<string, unknown>>(res.data)?.total ?? 0)
      .catch(() => 0),
    fetchAttributes()
      .then((res) => unwrapList<Record<string, unknown>>(res.data))
      .catch(() => [] as Record<string, unknown>[]),
  ])
  const mine = attrs.filter((a) => String(a.categoryId ?? '') === cat.id)
  return {
    products,
    attrs: mine.length,
    attrNames: mine.map((a) => String(a.name ?? '')),
    children: cat.children.length,
  }
}

async function openCatDelete(cat: CatNode) {
  catDelTarget.value = cat
  catDelImpact.value = { products: 0, attrs: 0, attrNames: [], children: cat.children.length }
  catDelDialog.value = true
  catDelImpact.value = await loadCatImpact(cat)
}

async function confirmCatDelete() {
  const cat = catDelTarget.value
  if (!cat) return
  catDeleting.value = true
  try {
    await deleteCategory(cat.id)
    ElMessage.success('已删除')
    catDelDialog.value = false
    if (selectedCategoryId.value === cat.id) {
      selectedCategoryId.value = ''
      selectedCategoryName.value = '全部类目'
    }
    await loadTree()
    await Promise.all([loadProductCounts(), loadRight()])
  } catch (e: unknown) {
    catDelDialog.value = false
    catReject.value = {
      name: cat.name,
      products: catDelImpact.value.products,
      attrs: catDelImpact.value.attrs,
      attrNames: catDelImpact.value.attrNames,
      msg: readErrMsg(e),
    }
    catRejectDialog.value = true
  } finally {
    catDeleting.value = false
  }
}

/* ===== 品牌：新增 / 编辑 ===== */
function openBrandCreate() {
  brandEditingId.value = null
  brandFormError.value = ''
  brandErrors.value = {}
  brandForm.value = {
    name: '',
    categoryId: selectedCategoryId.value,
    logo: '',
    sort: 0,
    status: 'ENABLED',
  }
  brandDialog.value = true
}

function openBrandEdit(row: BrandRow) {
  brandEditingId.value = row.id
  brandFormError.value = ''
  brandErrors.value = {}
  brandForm.value = {
    name: row.name,
    categoryId: row.categoryId,
    logo: row.logo,
    sort: row.sort,
    status: row.status || 'ENABLED',
  }
  brandDialog.value = true
}

async function submitBrand() {
  const errors: { name?: string; categoryId?: string } = {}
  const name = brandForm.value.name.trim()
  if (!name) errors.name = '请填写品牌名称'
  else if (name.length > 30) errors.name = '品牌名称为 1～30 字符'
  if (!brandForm.value.categoryId) errors.categoryId = '请选择类目'
  brandErrors.value = errors
  brandFormError.value = ''
  if (Object.keys(errors).length) return

  brandSaving.value = true
  try {
    await saveBrand(
      {
        name,
        logo: brandForm.value.logo || undefined,
        sort: Number(brandForm.value.sort) || 0,
        status: brandForm.value.status,
        categoryId: Number(brandForm.value.categoryId),
      },
      brandEditingId.value ?? undefined,
    )
    ElMessage.success(brandEditingId.value ? '已保存' : '已创建')
    brandDialog.value = false
    await loadRight()
  } catch (e: unknown) {
    brandFormError.value = readErrMsg(e) || '保存失败，请稍后重试'
  } finally {
    brandSaving.value = false
  }
}

/* ===== 品牌：删除确认（dlg-brand-del）===== */
async function openBrandDelete(row: BrandRow) {
  brandDelTarget.value = row
  brandDelDialog.value = true
  // 按类目筛选时行内的关联SPU 只统计该类目树下；删除校验用的是全量引用，取未筛选值
  if (selectedCategoryId.value) {
    try {
      const res = await fetchBrands({ page: 1, pageSize: 200 })
      const hit = unwrapPage<Record<string, unknown>>(res.data)?.records.find(
        (b) => String(b.id ?? '') === row.id,
      )
      if (hit) brandDelTarget.value = { ...row, spuCount: Number(hit.spuCount ?? row.spuCount) }
    } catch {
      /* 取不到就用行内值 */
    }
  }
}

async function confirmBrandDelete() {
  const row = brandDelTarget.value
  if (!row) return
  brandDeleting.value = true
  try {
    await deleteBrand(row.id)
    ElMessage.success('已删除')
    brandDelDialog.value = false
    await loadRight()
  } catch (e: unknown) {
    brandDelDialog.value = false
    delRejectTitle.value = '无法删除品牌'
    delRejectMsg.value = readErrMsg(e) || '删除失败，请稍后重试'
    delRejectDialog.value = true
  } finally {
    brandDeleting.value = false
  }
}

/* ===== 属性：新增 / 编辑 ===== */
function parseOptions(raw: string): string[] {
  return raw
    .split('/')
    .map((s) => s.trim())
    .filter(Boolean)
}

function openAttrCreate() {
  attrEditingId.value = null
  attrFormError.value = ''
  attrErrors.value = {}
  attrForm.value = {
    categoryId: selectedCategoryId.value,
    name: '',
    type: 'SPEC',
    options: '',
    sort: 0,
  }
  attrDialog.value = true
}

function openAttrEdit(row: AttrRow) {
  attrEditingId.value = row.id
  attrFormError.value = ''
  attrErrors.value = {}
  attrForm.value = {
    categoryId: row.categoryId,
    name: row.name,
    type: row.type === 'PARAM' ? 'PARAM' : 'SPEC',
    options: row.options.join(' / '),
    sort: row.sort,
  }
  attrDialog.value = true
}

async function submitAttr() {
  const errors: { categoryId?: string; name?: string; options?: string } = {}
  if (!attrForm.value.categoryId) errors.categoryId = '请先选择类目'
  const name = attrForm.value.name.trim()
  if (!name) errors.name = '请填写属性名称'
  else if (name.length > 30) errors.name = '属性名称为 1～30 字符'
  const options = parseOptions(attrForm.value.options)
  if (!options.length) errors.options = '请填写可选值'
  else if (options.some((v) => v.length > 20)) errors.options = '每个可选值为 1～20 字符'
  else if (new Set(options).size !== options.length) errors.options = '可选值不可重复'
  attrErrors.value = errors
  attrFormError.value = ''
  if (Object.keys(errors).length) return

  attrSaving.value = true
  try {
    await saveAttribute(
      {
        name,
        type: attrForm.value.type,
        options,
        sort: Number(attrForm.value.sort) || 0,
        categoryId: Number(attrForm.value.categoryId),
      },
      attrEditingId.value ?? undefined,
    )
    ElMessage.success(attrEditingId.value ? '已保存' : '已创建')
    attrDialog.value = false
    await loadRight()
  } catch (e: unknown) {
    attrFormError.value = readErrMsg(e) || '保存失败，请稍后重试'
  } finally {
    attrSaving.value = false
  }
}

/* ===== 属性：删除确认（dlg-attr-del）===== */
function openAttrDelete(row: AttrRow) {
  attrDelTarget.value = row
  attrDelDialog.value = true
}

async function confirmAttrDelete() {
  const row = attrDelTarget.value
  if (!row) return
  attrDeleting.value = true
  try {
    await deleteAttribute(row.id)
    ElMessage.success('已删除')
    attrDelDialog.value = false
    await loadRight()
  } catch (e: unknown) {
    attrDelDialog.value = false
    delRejectTitle.value = '无法删除属性'
    delRejectMsg.value = readErrMsg(e) || '删除失败，请稍后重试'
    delRejectDialog.value = true
  } finally {
    attrDeleting.value = false
  }
}

onMounted(load)
</script>

<template>
  <PageHead title="类目与品牌" :sub="pageSub" />

  <div class="split">
    <div class="card card-pad">
      <div class="page-head card-head">
        <div class="page-title card-title">类目</div>
        <button class="btn btn-ghost btn-sm" type="button" @click="openCatCreate('0')">+ 顶级</button>
      </div>
      <div
        class="tree-item cat-all"
        :class="{ active: !selectedCategoryId }"
        @click="selectCategory(null)"
      >
        全部类目
      </div>
      <p v-if="!categories.length" class="hint">暂无类目</p>
      <ul v-else class="tree">
        <li v-for="row in visibleRows" :key="row.node.id">
          <div
            class="tree-item cat-row"
            :class="[`depth-${Math.min(row.depth, 3)}`, { active: selectedCategoryId === row.node.id }]"
            @click="selectCategory(row.node)"
          >
            <span class="nm">
              <button
                class="chev"
                type="button"
                :aria-label="expanded.has(row.node.id) ? '收起' : '展开'"
                @click.stop="toggleExpand(row.node.id)"
              >
                {{ row.node.children.length ? (expanded.has(row.node.id) ? '▾' : '▸') : '' }}
              </button>
              <b>{{ row.node.name }}</b>
            </span>
            <span class="meta hint">{{ productCount(row.node.id) }} 商品</span>
            <button class="btn btn-ghost btn-sm" type="button" @click.stop="openCatEdit(row.node)">
              编辑
            </button>
            <button
              class="btn btn-ghost btn-sm"
              type="button"
              @click.stop="openCatDelete(row.node)"
            >
              删除
            </button>
          </div>
        </li>
      </ul>
    </div>

    <div>
      <div class="hint current-line">当前：<b>{{ selectedCategoryName }}</b></div>

      <div class="card card-pad card-gap">
        <div class="page-head card-head">
          <div class="page-title card-title">品牌</div>
          <button class="btn btn-ghost btn-sm" type="button" @click="openBrandCreate">
            + 品牌
          </button>
        </div>
        <p class="hint card-hint">
          筛选 = 归属本类目树 ∪ 本类目树下商品已引用的品牌；新增默认挂当前类目
        </p>
        <table class="table">
          <thead>
            <tr>
              <th>品牌名称</th>
              <th>归属类目</th>
              <th>Logo</th>
              <th class="num">关联SPU</th>
              <th class="num center">排序</th>
              <th class="col-op">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!brands.length">
              <td colspan="6" class="hint empty-cell">
                {{ selectedCategoryId ? '该类目下暂无关联品牌' : '暂无品牌' }}
              </td>
            </tr>
            <tr v-for="b in brands" :key="b.id">
              <td>{{ b.name }}</td>
              <td>{{ catName(b.categoryId) }}</td>
              <td>
                <img v-if="b.logo" class="brand-logo" :src="resolveMediaUrl(b.logo)" alt="" />
                <span v-else class="hint">—</span>
              </td>
              <td class="num">{{ b.spuCount }}</td>
              <td class="num center">{{ b.sort }}</td>
              <td class="col-op">
                <button class="btn btn-ghost btn-sm" type="button" @click="openBrandEdit(b)">
                  编辑
                </button>
                <button class="btn btn-ghost btn-sm" type="button" @click="openBrandDelete(b)">
                  删除
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="card card-pad">
        <div class="page-head card-head">
          <div class="page-title card-title">属性</div>
          <button class="btn btn-ghost btn-sm" type="button" @click="openAttrCreate">+ 属性</button>
        </div>
        <p class="hint card-hint">
          <b>规格</b>参与 SKU 组合（如 颜色 / 容量）；<b>参数</b>仅作详情展示（如 处理器）。
        </p>
        <table class="table">
          <thead>
            <tr>
              <th>属性名</th>
              <th>所属类目</th>
              <th>类型</th>
              <th class="code">可选值</th>
              <th class="col-op">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!attributes.length">
              <td colspan="5" class="hint empty-cell">
                {{ selectedCategoryId ? '该类目下暂无属性' : '暂无属性' }}
              </td>
            </tr>
            <tr v-for="a in attributes" :key="a.id">
              <td>{{ a.name }}</td>
              <td>{{ catName(a.categoryId) }}</td>
              <td>
                <StatusBadge :text="a.typeLabel" :tone="a.type === 'PARAM' ? 'amber' : 'coral'" />
              </td>
              <td class="code">{{ a.options.join(' / ') || '—' }}</td>
              <td class="col-op">
                <button class="btn btn-ghost btn-sm" type="button" @click="openAttrEdit(a)">
                  编辑
                </button>
                <button class="btn btn-ghost btn-sm" type="button" @click="openAttrDelete(a)">
                  删除
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>

  <!-- 新增 / 编辑类目（dlg-cat-form） -->
  <FormDialog
    v-model="catDialog"
    :title="catEditingId ? '编辑类目' : '新增类目'"
    icon="info"
    icon-text="✎"
    width="is-md"
    cancel-text="取消"
    confirm-text="保存"
    :loading="catSaving"
    @submit="submitCategory"
  >
    <p v-if="catFormError" class="error-text">{{ catFormError }}</p>
    <div class="field">
      <label class="label">类目名称<span class="req">*</span></label>
      <input v-model="catForm.name" class="input" maxlength="30" />
      <span class="hint">1～30 字符，同一上级类目下不能重名</span>
      <span v-if="catErrors.name" class="error-text">{{ catErrors.name }}</span>
    </div>
    <div class="field">
      <label class="label">上级类目<span class="req">*</span></label>
      <select v-model="catForm.parentId" class="select">
        <option value="0">—（顶级类目）</option>
        <option v-for="c in catParentOptions" :key="c.id" :value="c.id">{{ c.name }}</option>
      </select>
      <span class="hint">顶级类目共 {{ topCount }} 个；同样不能选自己或自己的下级类目</span>
    </div>
    <div class="form-grid">
      <div class="field">
        <label class="label">排序<span class="req">*</span></label>
        <input v-model.number="catForm.sort" class="input" type="number" min="0" max="999" />
        <span class="hint">0～999，同级越小越靠前</span>
        <span v-if="catErrors.sort" class="error-text">{{ catErrors.sort }}</span>
      </div>
      <div class="field">
        <label class="label">状态<span class="req">*</span></label>
        <select v-model="catForm.status" class="select">
          <option value="ENABLED">启用</option>
          <option value="DISABLED">禁用</option>
        </select>
        <span class="hint">禁用后不出现在商品编辑的类目下拉中</span>
      </div>
    </div>
    <div class="alert alert-info">类目被商品或属性引用时<b>不能删除</b>；如需合并请先迁移商品、删除或迁移属性。</div>
  </FormDialog>

  <!-- 删除类目（dlg-cat-del） -->
  <FormDialog
    v-model="catDelDialog"
    title="删除类目"
    icon="danger"
    width="is-sm"
    cancel-text="取消"
    confirm-text="确认删除"
    :loading="catDeleting"
    @submit="confirmCatDelete"
  >
    <p>确认删除类目「{{ catDelTarget?.name }}」？</p>
    <ul class="impact">
      <li>该类目下商品 <b>{{ catDelImpact.products }}</b> 个</li>
      <li>
        该类目下属性 <b>{{ catDelImpact.attrs }}</b> 个 · 下级类目
        <b>{{ catDelImpact.children }}</b> 个
      </li>
    </ul>
    <div class="alert alert-warn impact-alert">
      仅当类目下<b>无商品且无属性引用</b>时才能删除；存在引用会被系统拒绝，需先转移商品或删除属性。
    </div>
  </FormDialog>

  <!-- 类目被引用：拒绝（dlg-cat-reject） -->
  <NoticeDialog
    v-model="catRejectDialog"
    title="无法删除类目"
    icon="warn"
    width="is-sm"
    confirm-text="知道了"
  >
    <p>
      类目「{{ catReject.name }}」仍被 <b>{{ catReject.products }}</b> 个商品、<b>{{
        catReject.attrs
      }}</b> 个属性{{ catRejectNames }}引用，不能删除。
    </p>
    <p class="hint">请先把这些商品改到其他类目，并删除 / 迁移该类目下的属性，待引用归零后再删除。</p>
    <p v-if="catRejectExtra" class="hint">{{ catRejectExtra }}</p>
  </NoticeDialog>

  <!-- 新增 / 编辑品牌（dlg-brand-form） -->
  <FormDialog
    v-model="brandDialog"
    :title="brandEditingId ? '编辑品牌' : '新增品牌'"
    icon="info"
    icon-text="✎"
    width="is-md"
    cancel-text="取消"
    confirm-text="保存"
    :loading="brandSaving"
    @submit="submitBrand"
  >
    <p v-if="brandFormError" class="error-text">{{ brandFormError }}</p>
    <div class="field">
      <label class="label">品牌名称<span class="req">*</span></label>
      <input v-model="brandForm.name" class="input" maxlength="30" />
      <span class="hint">1～30 字符，同一类目下不能重名</span>
      <span v-if="brandErrors.name" class="error-text">{{ brandErrors.name }}</span>
    </div>
    <div class="field">
      <label class="label">归属类目<span class="req">*</span></label>
      <select v-model="brandForm.categoryId" class="select">
        <option value="">请选择类目</option>
        <option v-for="c in flatCats" :key="c.id" :value="c.id">{{ c.name }}</option>
      </select>
      <span class="hint">默认取当前选中类目；品牌在类目页按「归属类目树 ∪ 商品已引用」筛选</span>
      <span v-if="brandErrors.categoryId" class="error-text">{{ brandErrors.categoryId }}</span>
    </div>
    <div class="field">
      <label class="label">品牌 Logo</label>
      <OssImageUpload v-model="brandForm.logo" biz-type="BRAND" />
      <span class="hint">
        1 张，jpeg / png / webp，≤10MB，建议 256×256；展示走
        <span class="code">/admin/files/view</span> 代理。
      </span>
    </div>
    <div class="form-grid">
      <div class="field">
        <label class="label">排序</label>
        <input v-model.number="brandForm.sort" class="input" type="number" min="0" max="999" />
      </div>
      <div class="field">
        <label class="label">状态</label>
        <select v-model="brandForm.status" class="select">
          <option value="ENABLED">启用</option>
          <option value="DISABLED">禁用</option>
        </select>
      </div>
    </div>
  </FormDialog>

  <!-- 新增 / 编辑属性（dlg-attr-form） -->
  <FormDialog
    v-model="attrDialog"
    :title="attrEditingId ? '编辑属性' : '新增属性'"
    icon="info"
    icon-text="✎"
    width="is-md"
    cancel-text="取消"
    confirm-text="保存"
    :loading="attrSaving"
    @submit="submitAttr"
  >
    <p v-if="attrFormError" class="error-text">{{ attrFormError }}</p>
    <div class="field">
      <label class="label">所属类目<span class="req">*</span></label>
      <select v-model="attrForm.categoryId" class="select">
        <option value="">请选择类目</option>
        <option v-for="c in flatCats" :key="c.id" :value="c.id">{{ c.name }}</option>
      </select>
      <span v-if="attrErrors.categoryId" class="error-text">{{ attrErrors.categoryId }}</span>
    </div>
    <div class="form-grid">
      <div class="field">
        <label class="label">属性名称<span class="req">*</span></label>
        <input v-model="attrForm.name" class="input" maxlength="30" />
        <span class="hint">1～30 字符，同一类目下不能重名</span>
        <span v-if="attrErrors.name" class="error-text">{{ attrErrors.name }}</span>
      </div>
      <div class="field">
        <label class="label">属性类型<span class="req">*</span></label>
        <select v-model="attrForm.type" class="select">
          <option value="SPEC">规格（参与 SKU 组合）</option>
          <option value="PARAM">参数（仅详情展示）</option>
        </select>
      </div>
    </div>
    <div class="field">
      <label class="label">可选值<span class="req">*</span></label>
      <input v-model="attrForm.options" class="input" />
      <span class="hint">以「/」分隔，每项 1～20 字符，同一属性内不可重复；最多 2 个规格属性参与 SKU 组合</span>
      <span v-if="attrErrors.options" class="error-text">{{ attrErrors.options }}</span>
    </div>
    <div class="field">
      <label class="label">排序</label>
      <input v-model.number="attrForm.sort" class="input" type="number" min="0" max="999" />
    </div>
    <div class="alert alert-info">规格属性的可选值会作为商品编辑页「生成 SKU」的规格维度；已成 SKU 的值被删除时不会被覆盖。</div>
  </FormDialog>

  <!-- 删除品牌（dlg-brand-del） -->
  <FormDialog
    v-model="brandDelDialog"
    title="删除品牌"
    icon="danger"
    width="is-sm"
    cancel-text="取消"
    confirm-text="确认删除"
    :loading="brandDeleting"
    @submit="confirmBrandDelete"
  >
    <p>确认删除品牌「{{ brandDelTarget?.name }}」？</p>
    <ul class="impact">
      <li>该品牌关联商品 <b>{{ brandDelTarget?.spuCount ?? 0 }}</b> 个</li>
    </ul>
    <div class="alert alert-warn impact-alert">
      仅当品牌下<b>无商品引用</b>时才能删除；存在引用会被系统拒绝，请先把商品改到其他品牌或清空品牌。
    </div>
  </FormDialog>

  <!-- 删除属性（dlg-attr-del） -->
  <FormDialog
    v-model="attrDelDialog"
    title="删除属性"
    icon="danger"
    width="is-sm"
    cancel-text="取消"
    confirm-text="确认删除"
    :loading="attrDeleting"
    @submit="confirmAttrDelete"
  >
    <p>确认删除属性「{{ attrDelTarget?.name }}」？</p>
    <p class="hint">
      该属性为<b>{{ attrDelTypeLabel }}</b>类型，删除后商品编辑页不再提供该{{ attrDelTypeLabel }}维度；已生成的
      SKU 不受影响，但后续无法再按此维度组合。
    </p>
  </FormDialog>

  <!-- 品牌 / 属性删除被拒：后端拒绝原因 -->
  <NoticeDialog
    v-model="delRejectDialog"
    :title="delRejectTitle"
    icon="warn"
    width="is-sm"
    confirm-text="知道了"
  >
    <p>{{ delRejectMsg }}</p>
  </NoticeDialog>
</template>

<style scoped>
/* 原型 categories.html 内联样式的类化版本（不写行内 style） */
.card-head {
  margin-bottom: 12px;
}
.card-title {
  font-size: 16px;
}
.card-gap {
  margin-bottom: 16px;
}
.card-hint,
.current-line {
  margin-bottom: 10px;
}
.cat-all {
  margin-bottom: 8px;
}
.cat-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.cat-row .nm {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.cat-row .chev {
  width: 14px;
  flex-shrink: 0;
  border: 0;
  padding: 0;
  background: transparent;
  color: var(--slate);
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.cat-row.depth-1 {
  padding-left: 24px;
}
.cat-row.depth-2 {
  padding-left: 40px;
}
.cat-row.depth-3 {
  padding-left: 56px;
}
.brand-logo {
  width: 24px;
  height: 24px;
  object-fit: cover;
  border-radius: 6px;
  background: var(--cream);
  display: block;
}
.empty-cell {
  text-align: center;
  padding: 16px;
}
.impact-alert {
  margin-top: 12px;
}
</style>