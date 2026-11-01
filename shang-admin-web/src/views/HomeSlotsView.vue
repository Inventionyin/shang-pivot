<script setup lang="ts">
/**
 * 首页运营位 —— 对齐原型 .stitch/原型/admin/home-slots.html 与 dialogs-v2.html。
 * 弹窗：dlg-slot-pick（D2V-06 从商品库选品，下架商品不可选）、dlg-slot-remove（D2V-07 移除槽位条目）、
 *       dlg-slot-save（D2V-08 保存当前槽位，即时影响首页）、dlg-slot-filter（D2V-09 绑定商品已下架）。
 * 排序用「上移 / 下移」，序号越小越靠前。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import PageHead from '@/components/PageHead.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import OssImageUpload from '@/components/OssImageUpload.vue'
import FormDialog from '@/components/FormDialog.vue'
import NoticeDialog from '@/components/NoticeDialog.vue'
import {
  fetchHomeSlot,
  fetchHomeSlots,
  saveHomeSlot,
  type HomeSlotDetail,
  type HomeSlotItem,
  type HomeSlotSummary,
} from '@/api/homeSlots'
import { fetchProducts } from '@/api/products'
import { resolveMediaUrl } from '@/utils/media'
import { unwrapPage } from '@/utils/page'
import { usePermission } from '@/utils/permission'

type PickerRow = { id: string; name: string; status: string; thumb: string; price: string }

const MAX_ITEMS = 10

const { hasPermission } = usePermission()
const canEdit = computed(() => hasPermission('content:home'))

const slots = ref<HomeSlotSummary[]>([])
const activeCode = ref('')
const detail = ref<HomeSlotDetail | null>(null)
const items = ref<HomeSlotItem[]>([])
const loadingList = ref(false)
const loadingDetail = ref(false)
const saving = ref(false)
const saveError = ref('')

/** 编辑字段 */
const title = ref('')
const statusOn = ref(true)
const validFrom = ref('')
const validTo = ref('')

/* ===== D2V-06 从商品库选品 ===== */
const pickerOpen = ref(false)
const pickerKeyword = ref('')
const pickerRows = ref<PickerRow[]>([])
const pickerChecked = ref<Set<string>>(new Set())
const pickerLoading = ref(false)
const pickerError = ref('')

/* ===== D2V-07 移除槽位条目 ===== */
const removeOpen = ref(false)
const removeIndex = ref(-1)

/* ===== D2V-08 保存当前槽位 ===== */
const saveOpen = ref(false)

/* ===== D2V-09 绑定商品已下架 ===== */
const filterOpen = ref(false)

const isKeywordSlot = computed(() => activeCode.value === 'home_hot_keywords')
const isBannerSlot = computed(() => activeCode.value === 'home_banner')
const isSpuSlot = computed(
  () =>
    activeCode.value === 'home_hot_spus' ||
    activeCode.value.startsWith('home_promo_'),
)

const slotTitle = computed(() => title.value.trim() || detail.value?.title || '当前槽位')
const removeTarget = computed(() => items.value[removeIndex.value] ?? null)
const offShelfItems = computed(() => items.value.filter((it) => it.spuStatus === 'OFF'))
const inSlotIds = computed(
  () => new Set(items.value.map((it) => String(it.spuId ?? '')).filter((v) => v !== '')),
)
/** 保存确认弹窗回显的条目顺序（原型：101 → 143 → 248 → 127） */
const orderLine = computed(() =>
  items.value
    .map((it, i) => String(it.spuId ?? it.title ?? `#${i + 1}`))
    .join(' → '),
)
const validRangeText = computed(() => {
  if (!validFrom.value && !validTo.value) return '不限'
  const fmt = (v: string) => (v ? v.replace('T', ' ') : '不限')
  return `${fmt(validFrom.value)} ~ ${fmt(validTo.value)}`
})

function toLocalInput(iso?: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function fromLocalInput(local: string): string | null {
  if (!local) return null
  const d = new Date(local)
  if (Number.isNaN(d.getTime())) return null
  return d.toISOString()
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

async function loadSlots() {
  loadingList.value = true
  try {
    const res = await fetchHomeSlots()
    slots.value = Array.isArray(res.data) ? res.data : []
    if (!activeCode.value && slots.value.length) {
      activeCode.value = String(slots.value[0]!.code)
    }
  } catch {
    slots.value = []
  } finally {
    loadingList.value = false
  }
}

async function loadDetail(code: string) {
  if (!code) return
  loadingDetail.value = true
  try {
    const res = await fetchHomeSlot(code)
    detail.value = res.data
    title.value = String(res.data?.title || '')
    statusOn.value = String(res.data?.status || 'ON') === 'ON'
    validFrom.value = toLocalInput(res.data?.validFrom)
    validTo.value = toLocalInput(res.data?.validTo)
    items.value = (res.data?.items || []).map((it) => ({ ...it }))
  } catch {
    detail.value = null
    items.value = []
  } finally {
    loadingDetail.value = false
  }
}

watch(activeCode, (code) => {
  if (code) void loadDetail(code)
})

function selectSlot(code: string) {
  if (code === activeCode.value) return
  activeCode.value = code
}

function moveItem(idx: number, dir: -1 | 1) {
  const next = idx + dir
  if (next < 0 || next >= items.value.length) return
  const arr = [...items.value]
  const a = arr[idx]
  const b = arr[next]
  if (a == null || b == null) return
  arr[idx] = b
  arr[next] = a
  items.value = arr
}

function addKeyword() {
  if (items.value.length >= MAX_ITEMS) {
    ElMessage.warning('每个槽位最多 10 条')
    return
  }
  items.value = [...items.value, { title: '', linkType: 'NONE', status: 'ON' }]
}

function addBanner() {
  if (items.value.length >= MAX_ITEMS) {
    ElMessage.warning('每个槽位最多 10 条')
    return
  }
  items.value = [
    ...items.value,
    { imageKey: '', linkType: 'SPU', linkValue: '', title: '', status: 'ON' },
  ]
}

/* ===== D2V-07：移除槽位条目 ===== */
function openRemove(idx: number) {
  removeIndex.value = idx
  removeOpen.value = true
}

function confirmRemove() {
  const idx = removeIndex.value
  if (idx < 0) return
  items.value = items.value.filter((_, i) => i !== idx)
  ElMessage.success('已移除，保存当前槽位后生效')
  removeOpen.value = false
  removeIndex.value = -1
}

/* ===== D2V-06：从商品库选品 ===== */
async function openPicker() {
  pickerOpen.value = true
  pickerKeyword.value = ''
  pickerError.value = ''
  pickerChecked.value = new Set()
  await searchProducts()
}

async function searchProducts() {
  pickerLoading.value = true
  pickerError.value = ''
  try {
    const res = await fetchProducts({
      keyword: pickerKeyword.value || undefined,
      page: 1,
      pageSize: 20,
    })
    const page = unwrapPage<Record<string, unknown>>(res.data)
    const records = page?.records ?? []
    pickerRows.value = records.map((r) => ({
      id: String(r.id ?? ''),
      name: String(r.name ?? ''),
      status: String(r.status ?? ''),
      thumb: resolveMediaUrl(Array.isArray(r.pics) && r.pics.length ? String(r.pics[0]) : ''),
      price: String(r.priceRange ?? r.minPrice ?? r.price ?? '—'),
    }))
  } catch {
    pickerRows.value = []
  } finally {
    pickerLoading.value = false
  }
}

function togglePick(row: PickerRow) {
  if (row.status !== 'ON' || inSlotIds.value.has(row.id)) return
  const next = new Set(pickerChecked.value)
  if (next.has(row.id)) next.delete(row.id)
  else next.add(row.id)
  pickerChecked.value = next
  pickerError.value = ''
}

/** 「加入槽位」：勾选后加入当前槽位并排在末尾，同一商品在同一槽位内不可重复添加 */
function confirmPick() {
  const ids = pickerRows.value
    .filter((r) => pickerChecked.value.has(r.id) && !inSlotIds.value.has(r.id))
    .map((r) => r.id)
  if (!ids.length) {
    pickerError.value = '请先勾选要加入的商品'
    return
  }
  const room = MAX_ITEMS - items.value.length
  if (ids.length > room) {
    pickerError.value = `本槽位上限 ${MAX_ITEMS} 条，已选 ${items.value.length} 条，还可加入 ${room} 条`
    return
  }
  const added: HomeSlotItem[] = []
  for (const id of ids) {
    const row = pickerRows.value.find((r) => r.id === id)
    if (!row) continue
    added.push({
      spuId: row.id,
      spuName: row.name,
      spuStatus: row.status || 'ON',
      pic: row.thumb,
      price: row.price !== '—' ? row.price : null,
      linkType: 'SPU',
      linkValue: row.id,
      status: 'ON',
    })
  }
  items.value = [...items.value, ...added]
  ElMessage.success(`已加入 ${added.length} 个商品，保存当前槽位后生效`)
  pickerOpen.value = false
  pickerChecked.value = new Set()
}

/* ===== D2V-09：绑定商品已下架 ===== */
function openFilterNote() {
  if (!offShelfItems.value.length) return
  filterOpen.value = true
}

/* ===== D2V-08：保存当前槽位 ===== */
function openSave() {
  if (!canEdit.value) {
    ElMessage.warning('无编辑权限')
    return
  }
  if (!activeCode.value) return
  if (!title.value.trim()) {
    ElMessage.warning('请填写展示标题')
    return
  }
  saveError.value = ''
  saveOpen.value = true
}

async function confirmSave() {
  if (!activeCode.value) return
  saveError.value = ''
  saving.value = true
  try {
    const payloadItems = items.value.map((it, i) => ({
      sort: i + 1,
      spuId: it.spuId != null && it.spuId !== '' ? Number(it.spuId) : null,
      imageKey: it.imageKey || null,
      linkType: it.linkType || (it.spuId ? 'SPU' : 'NONE'),
      linkValue: it.linkValue ?? (it.spuId != null ? String(it.spuId) : null),
      title: it.title ?? null,
      status: it.status || 'ON',
    }))
    await saveHomeSlot(activeCode.value, {
      title: title.value.trim(),
      status: statusOn.value ? 'ON' : 'OFF',
      validFrom: fromLocalInput(validFrom.value),
      validTo: fromLocalInput(validTo.value),
      items: payloadItems,
    })
    ElMessage.success('当前槽位已保存')
    saveOpen.value = false
    await loadSlots()
    await loadDetail(activeCode.value)
  } catch (e: unknown) {
    saveError.value = readErrMsg(e) || '保存失败，请稍后重试'
  } finally {
    saving.value = false
  }
}

function spuTone(status?: string): 'forest' | 'brick' | 'amber' {
  if (status === 'ON') return 'forest'
  if (status === 'OFF') return 'brick'
  return 'amber'
}

function spuLabel(status?: string) {
  if (status === 'ON') return '上架'
  if (status === 'OFF') return '已下架'
  return status || '—'
}

onMounted(async () => {
  await loadSlots()
  if (activeCode.value) await loadDetail(activeCode.value)
})
</script>

<template>
  <PageHead
    title="首页运营位"
    sub="固定槽位配置：Banner / 活动位 / 搜索热词 / 热门精选 · 每槽位最多 10 条 · 可启停与排序 · 下架商品自动过滤"
  >
    <template #actions>
      <button
        class="btn btn-primary"
        type="button"
        :disabled="saving || loadingDetail || !canEdit || !activeCode"
        @click="openSave"
      >
        {{ saving ? '保存中…' : '保存当前槽位' }}
      </button>
    </template>
  </PageHead>

  <div class="slots-layout">
    <aside class="card card-pad slot-list" v-loading="loadingList">
      <div class="nav-group-title slot-list-title">槽位列表</div>
      <button
        v-for="s in slots"
        :key="String(s.code)"
        type="button"
        class="nav-item slot-item"
        :class="{ active: activeCode === s.code }"
        @click="selectSlot(String(s.code))"
      >
        <span>{{ s.title }}</span>
        <StatusBadge
          :text="s.status === 'ON' ? '启用' : '停用'"
          :tone="s.status === 'ON' ? 'forest' : 'amber'"
        />
      </button>
      <p class="hint slot-list-note">
        本期为固定槽位配置：槽位与字段固定，运营只改内容、启停与时间窗，不提供自由布局能力。
      </p>
    </aside>

    <section class="card card-pad slot-editor" v-loading="loadingDetail">
      <template v-if="detail">
        <div class="page-head slot-head">
          <div>
            <div class="page-title slot-head-title">
              {{ title || detail.title }} · <span class="code slot-code">{{ detail.code }}</span>
            </div>
            <div class="page-sub">
              <template v-if="isSpuSlot">绑定已上架商品；下架品 C 端自动过滤；不足时按系统配置的补位排序回填</template>
              <template v-else-if="isBannerSlot">配置 Banner 封面图与跳转（商品/类目/URL）</template>
              <template v-else-if="isKeywordSlot">热搜词写入条目标题；可空链接</template>
              <template v-else>配置槽位内容、启停与时间窗</template>
            </div>
          </div>
          <label class="switch" title="启用">
            <input v-model="statusOn" type="checkbox" :disabled="!canEdit" />
            <span class="track" />
          </label>
        </div>

        <div class="form-grid slot-form-grid">
          <div class="field">
            <label class="label">展示标题</label>
            <input v-model="title" class="input" maxlength="64" :disabled="!canEdit" />
          </div>
          <div class="field">
            <label class="label">生效时间</label>
            <div class="range">
              <input v-model="validFrom" type="datetime-local" class="input" :disabled="!canEdit" />
              <span>~</span>
              <input v-model="validTo" type="datetime-local" class="input" :disabled="!canEdit" />
            </div>
          </div>
        </div>

        <!-- SPU 选品 -->
        <template v-if="isSpuSlot">
          <div class="toolbar slot-toolbar">
            <button class="btn btn-primary btn-sm" type="button" :disabled="!canEdit" @click="openPicker">
              + 从商品库选品
            </button>
            <button
              class="btn btn-ghost btn-sm"
              type="button"
              :disabled="!offShelfItems.length"
              @click="openFilterNote"
            >
              下架商品过滤说明
            </button>
            <span class="hint">已选 {{ items.length }} / 上限 {{ MAX_ITEMS }}</span>
            <div class="spacer" />
            <span class="hint">用「上移 / 下移」调整顺序，序号越小越靠前</span>
          </div>
          <table class="table">
            <thead>
              <tr>
                <th class="num col-seq">序</th>
                <th>商品</th>
                <th class="code">SPU</th>
                <th class="num">售价</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="!items.length">
                <td colspan="6" class="hint empty-cell">暂无商品，请从商品库选品</td>
              </tr>
              <tr v-for="(it, idx) in items" :key="`${it.spuId}-${idx}`">
                <td class="num">{{ idx + 1 }}</td>
                <td>
                  <div class="spu-cell">
                    <img v-if="it.pic" class="thumb" :src="resolveMediaUrl(String(it.pic))" alt="" />
                    <span>{{ it.spuName || '—' }}</span>
                  </div>
                </td>
                <td class="code">{{ it.spuId }}</td>
                <td class="num">{{ it.price != null ? `¥${it.price}` : '—' }}</td>
                <td>
                  <StatusBadge :text="spuLabel(it.spuStatus)" :tone="spuTone(it.spuStatus)" />
                </td>
                <td class="col-op">
                  <button
                    class="btn btn-ghost btn-sm"
                    type="button"
                    :disabled="!canEdit || idx === 0"
                    @click="moveItem(idx, -1)"
                  >
                    上移
                  </button>
                  <button
                    class="btn btn-ghost btn-sm"
                    type="button"
                    :disabled="!canEdit || idx === items.length - 1"
                    @click="moveItem(idx, 1)"
                  >
                    下移
                  </button>
                  <button class="btn btn-ghost btn-sm" type="button" :disabled="!canEdit" @click="openRemove(idx)">
                    移除
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </template>

        <!-- Banner -->
        <template v-else-if="isBannerSlot">
          <div class="toolbar slot-toolbar">
            <button class="btn btn-primary btn-sm" type="button" :disabled="!canEdit" @click="addBanner">
              + 添加 Banner
            </button>
            <span class="hint">已选 {{ items.length }} / 上限 {{ MAX_ITEMS }}</span>
            <div class="spacer" />
            <span class="hint">用「上移 / 下移」调整顺序，序号越小越靠前</span>
          </div>
          <table class="table">
            <thead>
              <tr>
                <th class="num col-seq">序</th>
                <th>封面</th>
                <th>跳转类型</th>
                <th>跳转值</th>
                <th>标题</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="!items.length">
                <td colspan="6" class="hint empty-cell">暂无 Banner</td>
              </tr>
              <tr v-for="(it, idx) in items" :key="idx">
                <td class="num">{{ idx + 1 }}</td>
                <td class="cell-upload">
                  <OssImageUpload v-model="it.imageKey as string" biz-type="HOME_BANNER" />
                </td>
                <td>
                  <select v-model="it.linkType" class="select" :disabled="!canEdit">
                    <option value="SPU">商品</option>
                    <option value="CATEGORY">类目</option>
                    <option value="URL">URL</option>
                    <option value="NONE">无</option>
                  </select>
                </td>
                <td>
                  <input
                    v-model="it.linkValue"
                    class="input"
                    :disabled="!canEdit"
                    placeholder="SPU ID / 类目 ID / URL"
                  />
                </td>
                <td>
                  <input v-model="it.title" class="input" :disabled="!canEdit" />
                </td>
                <td class="col-op">
                  <button
                    class="btn btn-ghost btn-sm"
                    type="button"
                    :disabled="!canEdit || idx === 0"
                    @click="moveItem(idx, -1)"
                  >
                    上移
                  </button>
                  <button
                    class="btn btn-ghost btn-sm"
                    type="button"
                    :disabled="!canEdit || idx === items.length - 1"
                    @click="moveItem(idx, 1)"
                  >
                    下移
                  </button>
                  <button class="btn btn-ghost btn-sm" type="button" :disabled="!canEdit" @click="openRemove(idx)">
                    移除
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </template>

        <!-- 热搜词 -->
        <template v-else-if="isKeywordSlot">
          <div class="toolbar slot-toolbar">
            <button class="btn btn-primary btn-sm" type="button" :disabled="!canEdit" @click="addKeyword">
              + 添加热词
            </button>
            <span class="hint">已选 {{ items.length }} / 上限 {{ MAX_ITEMS }}</span>
            <div class="spacer" />
            <span class="hint">用「上移 / 下移」调整顺序，序号越小越靠前</span>
          </div>
          <table class="table">
            <thead>
              <tr>
                <th class="num col-seq">序</th>
                <th>热词</th>
                <th class="col-op">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="!items.length">
                <td colspan="3" class="hint empty-cell">暂无热词</td>
              </tr>
              <tr v-for="(it, idx) in items" :key="idx">
                <td class="num">{{ idx + 1 }}</td>
                <td>
                  <input
                    v-model="it.title"
                    class="input"
                    :disabled="!canEdit"
                    maxlength="32"
                    placeholder="热搜词"
                  />
                </td>
                <td class="col-op">
                  <button
                    class="btn btn-ghost btn-sm"
                    type="button"
                    :disabled="!canEdit || idx === 0"
                    @click="moveItem(idx, -1)"
                  >
                    上移
                  </button>
                  <button
                    class="btn btn-ghost btn-sm"
                    type="button"
                    :disabled="!canEdit || idx === items.length - 1"
                    @click="moveItem(idx, 1)"
                  >
                    下移
                  </button>
                  <button class="btn btn-ghost btn-sm" type="button" :disabled="!canEdit" @click="openRemove(idx)">
                    移除
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </template>

        <template v-else>
          <p class="hint">未知槽位类型，仍可编辑标题与启停后保存。</p>
        </template>
      </template>
      <p v-else class="hint">请选择左侧槽位</p>
    </section>
  </div>

  <!-- 从商品库选品（D2V-06 · dlg-slot-pick）：下架商品不可选 -->
  <FormDialog
    v-model="pickerOpen"
    title="从商品库选品"
    icon="info"
    icon-text="＋"
    width="is-xl"
    cancel-text="取消"
    confirm-text="加入槽位"
    @submit="confirmPick"
  >
    <p class="hint picker-hint">
      仅列出<b>已上架</b>商品；已下架商品不可选，需要先到「商品列表」重新上架。当前槽位已选
      <b>{{ items.length }}</b> 条，本槽位上限 <b>{{ MAX_ITEMS }}</b> 条。
    </p>
    <div class="picker-search">
      <input
        v-model="pickerKeyword"
        class="input"
        placeholder="搜索商品名"
        @keyup.enter="searchProducts"
      />
      <button class="btn btn-secondary btn-sm" type="button" @click="searchProducts">搜索</button>
    </div>
    <p v-if="pickerError" class="error-text">{{ pickerError }}</p>
    <table class="table" v-loading="pickerLoading">
      <thead>
        <tr>
          <th class="col-check" />
          <th>商品</th>
          <th class="code">SPU</th>
          <th class="num">售价</th>
          <th>状态</th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="!pickerRows.length">
          <td colspan="5" class="hint empty-cell">无匹配商品</td>
        </tr>
        <tr v-for="row in pickerRows" :key="row.id">
          <td class="col-check">
            <input
              type="checkbox"
              :checked="pickerChecked.has(row.id) || inSlotIds.has(row.id)"
              :disabled="row.status !== 'ON' || inSlotIds.has(row.id)"
              @change="togglePick(row)"
            />
          </td>
          <td :class="{ hint: row.status !== 'ON' }">{{ row.name }}</td>
          <td class="code" :class="{ hint: row.status !== 'ON' }">{{ row.id }}</td>
          <td class="num" :class="{ hint: row.status !== 'ON' }">{{ row.price }}</td>
          <td>
            <StatusBadge
              v-if="row.status === 'ON'"
              text="上架"
              tone="forest"
            />
            <StatusBadge v-else text="已下架 · 不可选" tone="brick" />
          </td>
        </tr>
      </tbody>
    </table>
    <div class="alert alert-info impact-alert">
      勾选后加入当前槽位并排在末尾，可在列表中用「上移 / 下移」调整顺序。同一商品在同一槽位内不可重复添加。
    </div>
  </FormDialog>

  <!-- 移除槽位条目（D2V-07 · dlg-slot-remove） -->
  <FormDialog
    v-model="removeOpen"
    title="移除槽位条目"
    icon="danger"
    icon-text="×"
    width="is-sm"
    cancel-text="取消"
    confirm-text="确认移除"
    @submit="confirmRemove"
  >
    <p>
      确认从「<b>{{ slotTitle }}</b>」中移除「{{ removeTarget?.spuName || removeTarget?.title }}」（SPU
      {{ removeTarget?.spuId }}）？
    </p>
    <p class="hint">
      移除后该条目不再出现在首页；商品本身不受影响，仍可在「商品列表」中管理。移除在<b>保存当前槽位</b>后对顾客端生效。
    </p>
  </FormDialog>

  <!-- 保存当前槽位（D2V-08 · dlg-slot-save）：即时影响首页 -->
  <FormDialog
    v-model="saveOpen"
    title="保存当前槽位"
    icon="info"
    icon-text="✓"
    width="is-md"
    cancel-text="取消"
    confirm-text="确认保存"
    :loading="saving"
    @submit="confirmSave"
  >
    <p v-if="saveError" class="error-text">{{ saveError }}</p>
    <p>将保存槽位「<b>{{ slotTitle }}</b>」的以下改动：</p>
    <ul class="impact">
      <li>
        内容条目 <b>{{ items.length }}</b> 条<template v-if="items.length">（顺序：{{ orderLine }}）</template>
      </li>
      <li>展示标题：<b>{{ title.trim() || '—' }}</b></li>
      <li>生效时间：<b>{{ validRangeText }}</b></li>
      <li>槽位状态：<b>{{ statusOn ? '启用' : '停用' }}</b></li>
    </ul>
    <div class="alert alert-warn impact-alert">
      保存后<b>即时影响首页</b>：顺序与启停立即生效，顾客端刷新即可看到；已下架商品由 C 端自动过滤，不占用展示位。
    </div>
  </FormDialog>

  <!-- 绑定商品已下架（D2V-09 · dlg-slot-filter）：过滤说明 -->
  <NoticeDialog
    v-model="filterOpen"
    :title="`有 ${offShelfItems.length} 个绑定商品已下架`"
    icon="warn"
    width="is-md"
    confirm-text="知道了"
  >
    <p>
      「{{ slotTitle }}」绑定的 {{ items.length }} 个商品中，有
      <b>{{ offShelfItems.length }}</b> 个当前已下架：
    </p>
    <ul class="impact">
      <li v-for="(it, i) in offShelfItems" :key="`${it.spuId}-${i}`">
        <b>{{ it.spuName || '—' }}</b> · SPU <b>{{ it.spuId }}</b> · 已下架
      </li>
    </ul>
    <div class="alert alert-info impact-alert">
      C 端按商品状态过滤：已下架商品<b>自动不展示</b>，无需手工移除；条目仍保留在槽位中，商品重新上架后自动恢复展示。
      若展示位数不足，系统会按「系统设置 · 交易」中的补位排序（sold / new）回填上架商品。
    </div>
  </NoticeDialog>
</template>

<style scoped>
.slots-layout {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}
.slot-list-title {
  margin-bottom: 8px;
}
.slot-list-note {
  margin-top: 14px;
}
.slot-list .slot-item {
  width: 100%;
  margin: 0 0 4px;
  display: flex;
  align-items: center;
  gap: 8px;
  border: 0;
  cursor: pointer;
  font: inherit;
  color: inherit;
  text-align: left;
}
.slot-item .badge {
  margin-left: auto;
}
.slot-head {
  margin-bottom: 14px;
}
.slot-head-title {
  font-size: 15px;
}
.slot-code {
  font-size: 12px;
  color: var(--slate);
}
.slot-form-grid {
  margin-bottom: 8px;
}
.slot-toolbar {
  margin-bottom: 12px;
}
.range {
  display: flex;
  align-items: center;
  gap: 8px;
}
.spu-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}
.thumb {
  width: 36px;
  height: 36px;
  border-radius: 6px;
  object-fit: cover;
  background: var(--cream);
}
.col-seq {
  width: 48px;
}
.col-check {
  width: 44px;
}
.cell-upload {
  min-width: 120px;
}
.empty-cell {
  text-align: center;
  padding: 16px;
}
.picker-hint {
  margin-bottom: 12px;
}
.picker-search {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.impact-alert {
  margin-top: 12px;
}
@media (max-width: 900px) {
  .slots-layout {
    grid-template-columns: 1fr;
  }
}
</style>