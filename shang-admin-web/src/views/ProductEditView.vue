<script setup lang="ts">
/**
 * 新增 / 编辑商品 —— 对齐原型 .stitch/原型/admin/product-edit.html 与
 * product-edit-schedule.html（上架方式）。
 * 弹窗：dlg-onshelf-fail（保存并上架校验失败）、dlg-draft（存为草稿）、
 *       dlg-gen-sku（生成 SKU 预览）、dlg-del-sku（删除 SKU 行）、dlg-upload-bad（主图不符合要求）。
 * 校验错误一律内联 .error-text，弹窗不关；提交中 loading 防重复。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHead from '@/components/PageHead.vue'
import FormDialog from '@/components/FormDialog.vue'
import NoticeDialog from '@/components/NoticeDialog.vue'
import { fetchBrands, fetchCategoryTree } from '@/api/categories'
import { fetchProduct, onShelf, saveProduct, type SkuSavePayload } from '@/api/products'
import { uploadFile } from '@/api/files'
import { resolveMediaUrl } from '@/utils/media'
import { unwrapList, unwrapPage } from '@/utils/page'

const route = useRoute()
const router = useRouter()
const productId = computed(() => route.params.id as string | undefined)
const isEdit = computed(() => Boolean(productId.value))

const form = ref({
  name: '',
  categoryId: '' as string,
  brandId: '' as string,
  subtitle: '',
  detailHtml: '',
  picUrl: '',
  /** 上架方式：NOW 立即 / SCHEDULE 定时 / DRAFT 仅草稿 */
  shelfMode: 'DRAFT' as 'NOW' | 'SCHEDULE' | 'DRAFT',
  /** 本地 datetime-local 值 yyyy-MM-ddTHH:mm */
  scheduleOnAt: '',
  /** 规格一维度名，如 容量 */
  spec1Name: '容量',
  /** 规格二维度名，如 颜色 */
  spec2Name: '颜色',
  /** 规格一可选值，用 / 分隔 */
  spec1Values: '',
  /** 规格二可选值，用 / 分隔 */
  spec2Values: '',
})

/** ISO → datetime-local（按东八区展示） */
function toLocalInput(iso: unknown): string {
  if (iso == null || iso === '') return ''
  const d = new Date(String(iso))
  if (Number.isNaN(d.getTime())) return ''
  const raw = d.toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai', hour12: false })
  return raw.slice(0, 16).replace(' ', 'T')
}

/** datetime-local → OffsetDateTime(+08:00) */
function toOffsetIso(local: string): string | null {
  const v = local.trim()
  if (!v) return null
  return `${v}:00+08:00`
}

type SkuRow = {
  title: string
  spec1: string
  spec2: string
  price: string
  cost: string
  stock: string
  barcode: string
}

type FieldErrors = { name?: string; categoryId?: string; skus?: string; schedule?: string }
type SkuError = { price?: string; stock?: string }

const skus = ref<SkuRow[]>([])
const categories = ref<{ id: string; name: string }[]>([])
const brands = ref<{ id: string; name: string }[]>([])
const saving = ref(false)
const errors = ref<FieldErrors>({})
const skuErrors = ref<Record<number, SkuError>>({})
/** 后端 msg 内联展示，不静默 */
const formError = ref('')

/* ===== 保存并上架校验失败（dlg-onshelf-fail / D3-01）===== */
const onFailDialog = ref(false)
const onFail = ref({ name: '', noPic: false, noPrice: false })

/* ===== 存为草稿（dlg-draft / D3-02）===== */
const draftDialog = ref(false)

/* ===== 生成 SKU 预览（dlg-gen-sku / D3-03）===== */
type Combo = { spec1: string; spec2: string; exist: SkuRow | null }
const genDialog = ref(false)
const combos = ref<Combo[]>([])

/* ===== 删除 SKU 行（dlg-del-sku / D3-04）===== */
const delSkuDialog = ref(false)
const delSkuIndex = ref(-1)
const delSkuLabel = computed(() => {
  const row = delSkuIndex.value >= 0 ? skus.value[delSkuIndex.value] : undefined
  if (!row) return ''
  return [row.spec1, row.spec2].filter(Boolean).join(' / ') || row.title || '单规格'
})

/* ===== 主图不符合要求（dlg-upload-bad / D3-05）===== */
const uploadBadDialog = ref(false)
const picInputRef = ref<HTMLInputElement | null>(null)
const uploading = ref(false)
const PIC_ACCEPT = ['image/jpeg', 'image/png', 'image/webp']
const PIC_MAX = 10 * 1024 * 1024

const pageSub = 'SPU 信息 + 规格组合自动生成 SKU · 含上架方式（立即 / 定时 / 仅草稿）'
const spec1Label = computed(() => form.value.spec1Name.trim() || '规格一')
const spec2Label = computed(() => form.value.spec2Name.trim() || '规格二')
const primaryLabel = computed(() =>
  form.value.shelfMode === 'SCHEDULE'
    ? '保存并定时上架'
    : form.value.shelfMode === 'DRAFT'
      ? '保存草稿'
      : '保存并上架',
)

function flattenCats(nodes: Record<string, unknown>[], prefix = ''): { id: string; name: string }[] {
  const out: { id: string; name: string }[] = []
  for (const n of nodes) {
    const name = `${prefix}${String(n.name ?? '')}`
    out.push({ id: String(n.id ?? ''), name })
    if (Array.isArray(n.children) && n.children.length) {
      out.push(...flattenCats(n.children as Record<string, unknown>[], `${name} / `))
    }
  }
  return out
}

function splitValues(raw: string): string[] {
  return raw
    .split(/[/、,，]/)
    .map((s) => s.trim())
    .filter(Boolean)
}

/** 从 SKU specs 抽出两个规格维度（优先常见键） */
function pickSpecs(
  specs: Record<string, unknown>,
): { key1: string; val1: string; key2: string; val2: string } {
  const entries = Object.entries(specs || {})
    .filter(([, v]) => v != null && String(v).trim() !== '')
    .map(([k, v]) => [k, String(v)] as const)

  const prefer1 = ['材质', '容量', '内存', '规格', '套装']
  const prefer2 = ['颜色', '色系']

  let key1 = ''
  let val1 = ''
  let key2 = ''
  let val2 = ''

  for (const k of prefer1) {
    const hit = entries.find(([ek]) => ek === k)
    if (hit) {
      key1 = hit[0]
      val1 = hit[1]
      break
    }
  }
  for (const k of prefer2) {
    const hit = entries.find(([ek]) => ek === k)
    if (hit) {
      key2 = hit[0]
      val2 = hit[1]
      break
    }
  }

  const rest = entries.filter(([k]) => k !== key1 && k !== key2)
  if (!key1 && rest.length) {
    const first = rest[0]!
    key1 = first[0]
    val1 = first[1]
  }
  if (!key2 && rest.length > (key1 ? 1 : 0)) {
    const idx = rest.findIndex(([k]) => k !== key1)
    if (idx >= 0) {
      const hit = rest[idx]!
      key2 = hit[0]
      val2 = hit[1]
    }
  }

  return {
    key1: key1 || '材质',
    val1,
    key2: key2 || '颜色',
    val2,
  }
}

/** 规格组合：按「规格一 × 规格二」全组合；两个维度都留空按单规格处理，重复值只出现一次 */
function buildCombos(): Combo[] {
  const v1 = [...new Set(splitValues(form.value.spec1Values))]
  const v2 = [...new Set(splitValues(form.value.spec2Values))]
  const a = v1.length ? v1 : ['']
  const b = v2.length ? v2 : ['']
  const out: Combo[] = []
  for (const s1 of a) {
    for (const s2 of b) {
      const exist = skus.value.find((s) => s.spec1 === s1 && s.spec2 === s2) ?? null
      out.push({ spec1: s1, spec2: s2, exist })
    }
  }
  return out
}

const newCount = computed(() => combos.value.filter((c) => !c.exist).length)
const existCount = computed(() => combos.value.length - newCount.value)
/** 表格上方提示里的全组合数（与预览弹窗同一套组合规则） */
const combosInTable = computed(() => buildCombos().length)

function openGenerate() {
  combos.value = buildCombos()
  genDialog.value = true
}

/** 确认生成：已存在的组合跳过（保留已填价格 / 库存 / 条码） */
function applyGenerate() {
  const next: SkuRow[] = combos.value.map((c) => {
    const title = [c.spec1, c.spec2].filter(Boolean).join(' / ') || form.value.name || '默认规格'
    return (
      c.exist ?? {
        title,
        spec1: c.spec1,
        spec2: c.spec2,
        price: '',
        cost: '',
        stock: '0',
        barcode: '',
      }
    )
  })
  skus.value = next
  skuErrors.value = {}
  genDialog.value = false
  ElMessage.success(
    newCount.value > 0
      ? `已生成 ${next.length} 行，其中新增 ${newCount.value} 行、跳过 ${existCount.value} 行已存在组合`
      : `没有新增组合，${existCount.value} 行已存在（跳过）`,
  )
}

function openDeleteSku(index: number) {
  delSkuIndex.value = index
  delSkuDialog.value = true
}

/** 行内改价 / 改库存后立刻撤掉该行的旧报错 */
function clearSkuError(index: number, key: keyof SkuError) {
  const cur = skuErrors.value[index]
  if (!cur || !cur[key]) return
  skuErrors.value = { ...skuErrors.value, [index]: { ...cur, [key]: undefined } }
}

function confirmDeleteSku() {
  const index = delSkuIndex.value
  delSkuDialog.value = false
  if (index < 0) return
  skus.value.splice(index, 1)
  skuErrors.value = {}
  ElMessage.success('已删除该行，保存后生效')
}

/* ===== 主图上传 ===== */
function pickPic() {
  if (uploading.value) return
  picInputRef.value?.click()
}

async function onPickPic(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  // 格式 / 体积不符合要求：走 dlg-upload-bad，不用顶部提示
  if (!PIC_ACCEPT.includes(file.type) || file.size > PIC_MAX) {
    uploadBadDialog.value = true
    return
  }
  uploading.value = true
  try {
    const res = await uploadFile(file, 'PRODUCT')
    const url = res.data?.url
    if (!url) {
      formError.value = '上传成功但未返回地址'
      return
    }
    form.value.picUrl = url
    ElMessage.success('上传成功')
  } catch (e: unknown) {
    formError.value = readErrMsg(e)
  } finally {
    uploading.value = false
  }
}

function formatMoney(v: string | number): string {
  const n = Number(v)
  if (!Number.isFinite(n)) return String(v)
  return `¥${n.toLocaleString('en-US', { maximumFractionDigits: 2 })}`
}

function readErrMsg(e: unknown): string {
  if (e && typeof e === 'object') {
    const direct = (e as { msg?: unknown }).msg
    if (typeof direct === 'string' && direct) return direct
    const nested = (e as { response?: { data?: { msg?: unknown } } }).response?.data?.msg
    if (typeof nested === 'string' && nested) return nested
  }
  return '保存失败，请稍后重试'
}

const hasPricedSku = computed(() => skus.value.some((s) => Number(s.price) > 0))

function buildPayload(status: string) {
  const pics = form.value.picUrl.trim() ? [form.value.picUrl.trim()] : []
  const k1 = spec1Label.value
  const k2 = spec2Label.value
  const skuPayload: SkuSavePayload[] = skus.value.map((s) => {
    const specs: Record<string, string> = {}
    if (s.spec1) specs[k1] = s.spec1
    if (s.spec2) specs[k2] = s.spec2
    return {
      title: [s.spec1, s.spec2].filter(Boolean).join(' / ') || s.title || form.value.name,
      price: Number(s.price) || 0,
      costPrice: s.cost ? Number(s.cost) : undefined,
      barCode: s.barcode || undefined,
      specs,
      status: 'ENABLED',
      initStock: Number(s.stock) || 0,
      warehouseId: 1,
    }
  })
  return {
    name: form.value.name.trim(),
    categoryId: form.value.categoryId ? Number(form.value.categoryId) : null,
    brandId: form.value.brandId ? Number(form.value.brandId) : null,
    subtitle: form.value.subtitle || undefined,
    detailHtml: form.value.detailHtml || undefined,
    pics,
    status,
    scheduleOnAt:
      form.value.shelfMode === 'SCHEDULE' ? toOffsetIso(form.value.scheduleOnAt) : null,
    clearScheduleOnAt: form.value.shelfMode !== 'SCHEDULE',
    skus: skuPayload,
  }
}

async function loadDetail() {
  if (!productId.value) return
  try {
    const res = await fetchProduct(productId.value)
    const data = res.data as { spu?: Record<string, unknown>; skus?: Record<string, unknown>[] }
    const spu = data?.spu || (res.data as Record<string, unknown>)
    if (!spu) return
    const pics = spu.pics
    form.value.name = String(spu.name ?? '')
    form.value.categoryId = spu.categoryId != null ? String(spu.categoryId) : ''
    form.value.brandId = spu.brandId != null ? String(spu.brandId) : ''
    form.value.subtitle = String(spu.subtitle ?? '')
    form.value.detailHtml = String(spu.detailHtml ?? spu.detail ?? '')
    form.value.picUrl = Array.isArray(pics) && pics[0] ? String(pics[0]) : ''
    const schedule = toLocalInput(spu.scheduleOnAt)
    form.value.scheduleOnAt = schedule
    if (schedule) {
      form.value.shelfMode = 'SCHEDULE'
    } else if (String(spu.status ?? '') === 'ON') {
      form.value.shelfMode = 'NOW'
    } else {
      form.value.shelfMode = 'DRAFT'
    }

    const skuList = data?.skus ?? []
    skus.value = skuList.map((s) => {
      const specs = (s.specs || {}) as Record<string, unknown>
      const picked = pickSpecs(specs)
      return {
        title: String(s.title ?? ''),
        spec1: picked.val1,
        spec2: picked.val2,
        price: s.price != null ? String(s.price) : '',
        cost: s.costPrice != null ? String(s.costPrice) : '',
        stock: s.stock != null ? String(s.stock) : '0',
        barcode: String(s.barCode ?? ''),
      }
    })

    if (skuList.length) {
      const firstSku = skuList[0]!
      const first = pickSpecs((firstSku.specs || {}) as Record<string, unknown>)
      form.value.spec1Name = first.key1
      form.value.spec2Name = first.key2
    }
    const s1s = [...new Set(skus.value.map((s) => s.spec1).filter(Boolean))]
    const s2s = [...new Set(skus.value.map((s) => s.spec2).filter(Boolean))]
    form.value.spec1Values = s1s.join(' / ')
    form.value.spec2Values = s2s.join(' / ')
  } catch {
    ElMessage.error('加载商品失败')
  }
}

/** 主体字段 + SKU 行内校验（全部内联 .error-text） */
function validateForm(): boolean {
  const next: FieldErrors = {}
  const name = form.value.name.trim()
  if (!name) next.name = '请填写商品名称'
  else if (name.length > 60) next.name = '商品名称为 1～60 字符'
  else if (/[\uD83C-\uDBFF][\uDC00-\uDFFF]|[\u2600-\u27BF]/.test(name))
    next.name = '商品名称不允许表情符号'
  if (!form.value.categoryId) next.categoryId = '请选择类目'
  if (!skus.value.length) next.skus = '请先生成至少一个 SKU'
  errors.value = next
  formError.value = ''

  const rowErrors: Record<number, SkuError> = {}
  skus.value.forEach((s, i) => {
    const item: SkuError = {}
    const price = s.price.trim()
    if (!price) item.price = '请填写售价'
    else if (!/^\d+(\.\d{1,2})?$/.test(price)) item.price = '售价必填且最多两位小数'
    const stock = s.stock.trim()
    if (stock === '' || !/^\d+$/.test(stock)) item.stock = '库存 ≥0 整数'
    if (Object.keys(item).length) rowErrors[i] = item
  })
  skuErrors.value = rowErrors
  return !Object.keys(next).length && !Object.keys(rowErrors).length
}

function openOnShelfFail() {
  onFail.value = {
    name: form.value.name.trim() || '（未命名商品）',
    noPic: !form.value.picUrl.trim(),
    noPrice: !skus.value.length || !hasPricedSku.value,
  }
  onFailDialog.value = true
}

async function persist(mode: 'DRAFT' | 'NOW' | 'SCHEDULE'): Promise<boolean> {
  saving.value = true
  formError.value = ''
  try {
    // 保存时先按草稿写入；立即上架再调 onShelf，定时上架按 scheduleOnAt 交给 Job
    const payload = buildPayload('DRAFT')
    if (mode === 'NOW') {
      payload.scheduleOnAt = null
      payload.clearScheduleOnAt = true
    }
    const res = await saveProduct(payload, productId.value)
    let id = productId.value
    if (!id) id = String(res.data ?? '')
    if (mode === 'NOW' && id) {
      await onShelf(id)
      ElMessage.success('已保存并立即上架')
    } else if (mode === 'SCHEDULE') {
      ElMessage.success('已保存，将于定时时间自动上架')
    } else {
      ElMessage.success('草稿已保存')
    }
    if (!productId.value && id) await router.replace(`/products/edit/${id}`)
    else await loadDetail()
    return true
  } catch (e: unknown) {
    formError.value = readErrMsg(e)
    return false
  } finally {
    saving.value = false
  }
}

/** publish=false 走「存草稿」；publish=true 按上架方式（立即 / 定时 / 仅草稿） */
async function onSave(publish: boolean) {
  if (saving.value) return
  if (!validateForm()) return
  const mode: 'DRAFT' | 'NOW' | 'SCHEDULE' = publish ? form.value.shelfMode : 'DRAFT'

  // 存为草稿：先确认（D3-02）；立即 / 定时上架：先过主图与售价校验（D3-01）
  if (mode === 'DRAFT') {
    draftDialog.value = true
    return
  }
  if (!form.value.picUrl.trim() || !hasPricedSku.value) {
    openOnShelfFail()
    return
  }
  if (mode === 'SCHEDULE') {
    const raw = form.value.scheduleOnAt.trim()
    const iso = toOffsetIso(raw)
    const at = iso ? new Date(iso) : null
    if (!raw || !at || Number.isNaN(at.getTime())) {
      errors.value = { schedule: '请填写定时上架时间' }
      return
    }
    if (at.getTime() <= Date.now()) {
      errors.value = { schedule: '定时上架时间须晚于当前时间' }
      return
    }
  }
  await persist(mode)
}

async function confirmDraft() {
  const ok = await persist('DRAFT')
  if (ok) draftDialog.value = false
}

onMounted(async () => {
  try {
    const [catRes, brandRes] = await Promise.all([
      fetchCategoryTree(),
      fetchBrands({ page: 1, pageSize: 200 }),
    ])
    categories.value = flattenCats(unwrapList<Record<string, unknown>>(catRes.data))
    const brandPage = unwrapPage<Record<string, unknown>>(brandRes.data)
    brands.value = (brandPage?.records ?? unwrapList<Record<string, unknown>>(brandRes.data)).map(
      (b) => ({ id: String(b.id ?? ''), name: String(b.name ?? '') }),
    )
  } catch {
    /* ignore */
  }
  await loadDetail()
})

watch(productId, () => loadDetail())
</script>

<template>
  <PageHead :title="isEdit ? '编辑商品' : '新增商品'" :sub="pageSub">
    <template #actions>
      <RouterLink class="btn btn-secondary" to="/products">取消</RouterLink>
      <button class="btn btn-secondary" type="button" :disabled="saving" @click="onSave(false)">
        存草稿
      </button>
      <button class="btn btn-primary" type="button" :disabled="saving" @click="onSave(true)">
        {{ primaryLabel }}
      </button>
    </template>
  </PageHead>

  <div class="card card-pad card-gap">
    <div class="page-title card-title">基本信息</div>
    <p v-if="formError" class="error-text">{{ formError }}</p>
    <div class="form-grid">
      <div class="field full">
        <label class="label">商品名称 (SPU)<span class="req">*</span></label>
        <input v-model="form.name" class="input" maxlength="60" placeholder="请输入商品名称" />
        <span class="hint">1～60 字符；不允许表情符号</span>
        <span v-if="errors.name" class="error-text">{{ errors.name }}</span>
      </div>
      <div class="field">
        <label class="label">类目<span class="req">*</span></label>
        <select v-model="form.categoryId" class="select">
          <option value="">请选择类目</option>
          <option v-for="c in categories" :key="c.id" :value="c.id">{{ c.name }}</option>
        </select>
        <span v-if="errors.categoryId" class="error-text">{{ errors.categoryId }}</span>
      </div>
      <div class="field">
        <label class="label">品牌</label>
        <select v-model="form.brandId" class="select">
          <option value="">请选择品牌</option>
          <option v-for="b in brands" :key="b.id" :value="b.id">{{ b.name }}</option>
        </select>
      </div>
      <div class="field full">
        <label class="label">副标题</label>
        <input v-model="form.subtitle" class="input" maxlength="100" placeholder="选填" />
        <span class="hint">≤100 字符</span>
      </div>
      <div class="field full">
        <label class="label">主图</label>
        <div class="pic-row">
          <div v-if="form.picUrl" class="thumb pic-thumb">
            <img :src="resolveMediaUrl(form.picUrl)" alt="" />
            <button class="pic-x" type="button" title="移除" @click="form.picUrl = ''">×</button>
          </div>
          <button class="pic-add" type="button" :disabled="uploading" @click="pickPic">
            {{ uploading ? '…' : '+' }}
          </button>
          <input
            ref="picInputRef"
            class="pic-file"
            type="file"
            accept="image/jpeg,image/png,image/webp"
            @change="onPickPic"
          />
        </div>
        <span class="hint">
          jpeg / png / webp，≤10MB，建议 256×256；上传至 MinIO，展示走
          <span class="code">/admin/files/view</span> 代理。上架至少需要 1 张主图。
        </span>
      </div>
      <div class="field full">
        <label class="label">商品详情</label>
        <textarea
          v-model="form.detailHtml"
          class="textarea"
          rows="3"
          placeholder="文本 / HTML"
        ></textarea>
      </div>
    </div>
  </div>

  <div class="card card-pad card-gap">
    <div class="page-title card-title">上架方式</div>
    <div class="form-grid">
      <div class="field">
        <label class="label">上架方式<span class="req">*</span></label>
        <select v-model="form.shelfMode" class="select">
          <option value="NOW">立即上架</option>
          <option value="SCHEDULE">定时上架</option>
          <option value="DRAFT">仅存草稿</option>
        </select>
        <span class="hint">仅存草稿 = 保存为草稿，不进入顾客端</span>
      </div>
      <div class="field">
        <label class="label">定时上架时间<span class="req">*</span></label>
        <input
          v-model="form.scheduleOnAt"
          class="input"
          type="datetime-local"
          :disabled="form.shelfMode !== 'SCHEDULE'"
        />
        <span class="hint">
          须晚于当前时间，精确到分钟；到点由系统自动上架，校验规则与立即上架一致
        </span>
        <span v-if="errors.schedule" class="error-text">{{ errors.schedule }}</span>
      </div>
    </div>
    <div class="alert alert-info">
      定时期间商品保持「草稿/下架」状态，顾客端不可见；到点校验通过才上架，校验失败保留草稿并记录日志。
    </div>
  </div>

  <div class="card card-pad card-gap">
    <div class="page-head card-head">
      <div class="page-title card-title">规格与 SKU</div>
      <button class="btn btn-ghost btn-sm" type="button" @click="openGenerate">生成 SKU</button>
    </div>
    <p class="hint card-hint">
      先填写规格维度名称与可选值（用 / 分隔），点「生成 SKU」按全组合生成；也可在表格内逐行改规格值。规格一/规格二最多
      2 个维度。
    </p>
    <div class="form-grid form-grid-gap">
      <div class="field">
        <label class="label">规格一 · 名称</label>
        <input v-model="form.spec1Name" class="input" placeholder="如 容量 / 内存" />
      </div>
      <div class="field">
        <label class="label">规格一 · 可选值</label>
        <input
          v-model="form.spec1Values"
          class="input"
          placeholder="用 / 分隔，如 128GB / 256GB / 512GB"
        />
      </div>
      <div class="field">
        <label class="label">规格二 · 名称</label>
        <input v-model="form.spec2Name" class="input" placeholder="如 颜色" />
      </div>
      <div class="field">
        <label class="label">规格二 · 可选值</label>
        <input
          v-model="form.spec2Values"
          class="input"
          placeholder="用 / 分隔，如 原色钛金属 / 白色钛金属"
        />
      </div>
    </div>
    <p class="hint card-hint">
      当前 {{ skus.length }} 个 SKU（{{ isEdit ? '已保存' : '待保存' }}）；按「{{ spec1Label }} ×
      {{ spec2Label }}」全组合共 {{ combosInTable }} 种，生成时已存在的组合会跳过
    </p>
    <p v-if="errors.skus" class="error-text card-hint">{{ errors.skus }}</p>
    <div class="table-wrap">
      <table class="table sku-table">
        <thead>
          <tr>
            <th>{{ spec1Label }}</th>
            <th>{{ spec2Label }}</th>
            <th class="num">售价</th>
            <th class="num">成本价</th>
            <th class="num">库存</th>
            <th class="code">条码</th>
            <th class="col-op">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!skus.length">
            <td colspan="7" class="hint empty-cell">
              填写规格后点「生成 SKU」，或保存时至少保留一行
            </td>
          </tr>
          <tr v-for="(sku, i) in skus" :key="i">
            <td>
              <input v-model="sku.spec1" class="input" :placeholder="spec1Label" />
            </td>
            <td>
              <input v-model="sku.spec2" class="input" :placeholder="spec2Label" />
            </td>
            <td class="num">
              <input
                v-model="sku.price"
                class="input"
                inputmode="decimal"
                @input="clearSkuError(i, 'price')"
              />
              <span v-if="skuErrors[i]?.price" class="error-text">{{ skuErrors[i]?.price }}</span>
            </td>
            <td class="num">
              <input v-model="sku.cost" class="input" inputmode="decimal" />
            </td>
            <td class="num">
              <input
                v-model="sku.stock"
                class="input"
                inputmode="numeric"
                @input="clearSkuError(i, 'stock')"
              />
              <span v-if="skuErrors[i]?.stock" class="error-text">{{ skuErrors[i]?.stock }}</span>
            </td>
            <td class="code">
              <input v-model="sku.barcode" class="input" />
            </td>
            <td class="col-op">
              <button class="btn btn-ghost btn-sm" type="button" @click="openDeleteSku(i)">
                删
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p class="hint card-hint">
      库存为 0 允许上架：顾客端可展示，下单时再校验可售库存。SKU 行内校验：售价必填且最多两位小数、库存
      ≥0 整数。
    </p>
  </div>

  <!-- 保存并上架校验失败（dlg-onshelf-fail / D3-01） -->
  <NoticeDialog
    v-model="onFailDialog"
    title="无法上架"
    icon="warn"
    width="is-md"
    confirm-text="去补充"
  >
    <p>商品「{{ onFail.name }}」不满足上架条件，请修正后重试：</p>
    <ul class="impact">
      <li v-if="onFail.noPic">上架需要至少一张主图</li>
      <li v-if="onFail.noPrice">上架需要至少一个已填售价的 SKU</li>
    </ul>
    <div class="alert alert-info impact-alert">
      上架校验：主图非空、至少 1 个启用 SKU、售价 &gt; 0。库存为 0 不阻止上架。校验不通过时<b>停留本页</b>，已填内容不丢失。
    </div>
  </NoticeDialog>

  <!-- 存为草稿（dlg-draft / D3-02） -->
  <FormDialog
    v-model="draftDialog"
    title="存为草稿"
    icon="info"
    width="is-sm"
    cancel-text="取消"
    confirm-text="确认保存"
    :loading="saving"
    @submit="confirmDraft"
  >
    <p>
      保存为草稿后商品状态为 <span class="badge badge-amber">草稿</span>，顾客端不可见，可随时回到本页继续编辑或上架。
    </p>
  </FormDialog>

  <!-- 生成 SKU 预览（dlg-gen-sku / D3-03） -->
  <FormDialog
    v-model="genDialog"
    title="生成 SKU"
    icon="info"
    icon-text="⚙"
    width="is-lg"
    cancel-text="取消"
    confirm-text="确认生成"
    @submit="applyGenerate"
  >
    <p class="hint gen-line">
      按「{{ spec1Label }} × {{ spec2Label }}」全组合生成，将新增 <b>{{ newCount }}</b>
      行；已存在的组合会被跳过，不覆盖已填价格。
    </p>
    <p class="hint gen-line">
      共 <b>{{ combos.length }}</b> 行，其中 <b>{{ existCount }}</b> 行已存在（显示现有价格/库存，将跳过），<b>{{
        newCount
      }}</b> 行为新增待填。
    </p>
    <table class="table">
      <thead>
        <tr>
          <th>{{ spec1Label }}</th>
          <th>{{ spec2Label }}</th>
          <th class="num">售价</th>
          <th class="num">库存</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(c, i) in combos" :key="i">
          <td>{{ c.spec1 || '单规格' }}</td>
          <td>{{ c.spec2 || '—' }}</td>
          <td class="num" :class="{ hint: !c.exist }">
            {{ c.exist ? formatMoney(c.exist.price) : '待填' }}
          </td>
          <td class="num" :class="{ hint: !c.exist }">{{ c.exist ? c.exist.stock : '待填' }}</td>
        </tr>
      </tbody>
    </table>
    <div class="alert alert-info impact-alert">
      规格值重复或为空时不生成；两个规格维度都留空则按单规格 SKU 处理。
    </div>
  </FormDialog>

  <!-- 删除 SKU 行（dlg-del-sku / D3-04） -->
  <FormDialog
    v-model="delSkuDialog"
    title="删除 SKU"
    icon="danger"
    width="is-sm"
    cancel-text="取消"
    confirm-text="确认删除"
    @submit="confirmDeleteSku"
  >
    <p>确认删除规格组合「{{ delSkuLabel }}」？</p>
    <p class="hint">保存后该 SKU 及其库存记录一并删除，不可恢复。</p>
  </FormDialog>

  <!-- 主图不符合要求（dlg-upload-bad / D3-05） -->
  <NoticeDialog
    v-model="uploadBadDialog"
    title="图片不符合要求"
    icon="warn"
    width="is-sm"
    confirm-text="重新选择"
  >
    <p>仅支持 jpeg / png / webp，单张不超过 10MB。</p>
    <p class="hint">上传失败可重试，不影响已填写的表单内容。</p>
  </NoticeDialog>
</template>

<style scoped>
/* 原型 product-edit.html 内联样式的类化版本（不写行内 style） */
.card-head,
.card-title,
.card-hint {
  margin-bottom: 14px;
}
.card-title {
  font-size: 15px;
}
.card-gap {
  margin-bottom: 16px;
}
.form-grid-gap {
  margin-bottom: 16px;
}
.req {
  color: var(--brick);
  margin-left: 2px;
}
.empty-cell {
  text-align: center;
  padding: 16px;
}
.impact-alert {
  margin-top: 12px;
}
.gen-line {
  margin-bottom: 12px;
}
.pic-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.pic-thumb {
  position: relative;
  width: 64px;
  height: 64px;
  overflow: hidden;
}
.pic-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.pic-x {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 18px;
  height: 18px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  font-size: 12px;
  line-height: 18px;
  text-align: center;
  cursor: pointer;
}
.pic-add {
  width: 64px;
  height: 64px;
  display: grid;
  place-items: center;
  color: var(--slate);
  background: var(--sand);
  border: 1px dashed var(--border);
  border-radius: 8px;
  font-family: inherit;
  font-size: 18px;
  cursor: pointer;
}
.pic-add:hover:not(:disabled) {
  border-color: var(--amber);
  color: var(--ink);
}
.pic-add:disabled {
  cursor: wait;
  opacity: 0.75;
}
.pic-file {
  display: none;
}
.sku-table {
  table-layout: fixed;
  width: 100%;
  min-width: 880px;
}
.sku-table th:nth-child(1),
.sku-table td:nth-child(1) {
  width: 14%;
}
.sku-table th:nth-child(2),
.sku-table td:nth-child(2) {
  width: 16%;
}
.sku-table th:nth-child(3),
.sku-table td:nth-child(3),
.sku-table th:nth-child(4),
.sku-table td:nth-child(4) {
  width: 13%;
}
.sku-table th:nth-child(5),
.sku-table td:nth-child(5) {
  width: 12%;
}
.sku-table th:nth-child(6),
.sku-table td:nth-child(6) {
  width: 17%;
}
.sku-table th:nth-child(7),
.sku-table td:nth-child(7) {
  width: 15%;
}
.sku-table .input {
  width: 100%;
  min-width: 0;
}
.sku-table .error-text {
  display: block;
  margin-top: 4px;
}
</style>