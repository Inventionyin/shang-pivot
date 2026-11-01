<script setup lang="ts">
/**
 * 字典管理 —— 对齐原型 .stitch/原型/admin/dicts.html 与弹窗图鉴 dialogs.html。
 * 弹窗：dlg-dict-type-form（新增/编辑字典类型 · D11-01）、dlg-dict-item-form（新增/编辑字典项 · D11-02）、
 *       dlg-dict-item-del（删除字典项 · D11-03）、dlg-dict-del（删除字典类型 · 危险确认）。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import PageHead from '@/components/PageHead.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import FormDialog from '@/components/FormDialog.vue'
import {
  deleteDict,
  deleteDictItem,
  fetchDictItems,
  fetchDicts,
  saveDict,
  saveDictItem,
} from '@/api/dicts'
import { unwrapList, unwrapPage } from '@/utils/page'

type DictRow = {
  id: string
  code: string
  name: string
  remark: string
}

type ItemRow = {
  id: string
  dictId: string
  label: string
  value: string
  sort: number
  status: string
}

const dicts = ref<DictRow[]>([])
const items = ref<ItemRow[]>([])
const selectedId = ref<string | null>(null)
const keyword = ref('')
const itemsLoading = ref(false)

/* ===== 新增 / 编辑字典类型（dlg-dict-type-form）===== */
const dictDialog = ref(false)
const editingDictId = ref<string | null>(null)
const dictSaving = ref(false)
const dictFormError = ref('')
const dictForm = ref({ code: '', name: '', remark: '' })
const dictErrors = ref<{ code?: string; name?: string }>({})

/* ===== 新增 / 编辑字典项（dlg-dict-item-form）===== */
const itemDialog = ref(false)
const editingItemId = ref<string | null>(null)
const itemSaving = ref(false)
const itemFormError = ref('')
const itemForm = ref<{ label: string; value: string; sort: number | string; status: string }>({
  label: '',
  value: '',
  sort: 0,
  status: 'ENABLED',
})
const itemErrors = ref<{ label?: string; value?: string; sort?: string }>({})

/* ===== 删除字典项（dlg-dict-item-del）===== */
const itemDelDialog = ref(false)
const itemDelTarget = ref<ItemRow | null>(null)
const itemDeleting = ref(false)
const itemDelError = ref('')

/* ===== 删除字典类型（dlg-dict-del）===== */
const dictDelDialog = ref(false)
const dictDelTarget = ref<DictRow | null>(null)
const dictDeleting = ref(false)
const dictDelError = ref('')

const selected = computed(() => dicts.value.find((d) => d.id === selectedId.value) ?? null)

const filteredDicts = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return dicts.value
  return dicts.value.filter(
    (d) =>
      d.code.toLowerCase().includes(q) ||
      d.name.toLowerCase().includes(q) ||
      d.remark.toLowerCase().includes(q),
  )
})

function readErrMsg(e: unknown): string {
  if (e && typeof e === 'object') {
    const direct = (e as { msg?: unknown }).msg
    if (typeof direct === 'string' && direct) return direct
    const nested = (e as { response?: { data?: { msg?: unknown } } }).response?.data?.msg
    if (typeof nested === 'string' && nested) return nested
  }
  return ''
}

async function loadDicts() {
  try {
    const res = await fetchDicts({ page: 1, size: 200 })
    const page = unwrapPage<Record<string, unknown>>(res.data)
    const list = page?.records ?? unwrapList<Record<string, unknown>>(res.data)
    const prev = selectedId.value
    dicts.value = list.map((d) => ({
      id: String(d.id ?? ''),
      code: String(d.code ?? ''),
      name: String(d.name ?? ''),
      remark: String(d.remark ?? ''),
    }))
    if (dicts.value.length) {
      const still = prev && dicts.value.some((d) => d.id === prev)
      selectedId.value = still ? prev : dicts.value[0]!.id
    } else {
      selectedId.value = null
      items.value = []
    }
  } catch {
    dicts.value = []
  }
}

async function loadItems() {
  if (!selectedId.value) {
    items.value = []
    return
  }
  itemsLoading.value = true
  try {
    const res = await fetchDictItems(selectedId.value)
    items.value = unwrapList<Record<string, unknown>>(res.data).map((i) => ({
      id: String(i.id ?? ''),
      dictId: String(i.dictId ?? selectedId.value),
      label: String(i.label ?? ''),
      value: String(i.value ?? ''),
      sort: Number(i.sort ?? 0),
      status: String(i.status ?? 'ENABLED'),
    }))
  } catch {
    items.value = []
  } finally {
    itemsLoading.value = false
  }
}

watch(selectedId, () => {
  void loadItems()
})

/* ===== 字典类型：新增 / 编辑 ===== */
function openCreateDict() {
  editingDictId.value = null
  dictFormError.value = ''
  dictErrors.value = {}
  dictForm.value = { code: '', name: '', remark: '' }
  dictDialog.value = true
}

function openEditDict(row: DictRow) {
  editingDictId.value = row.id
  dictFormError.value = ''
  dictErrors.value = {}
  dictForm.value = { code: row.code, name: row.name, remark: row.remark }
  dictDialog.value = true
}

async function submitDict() {
  const next: { code?: string; name?: string } = {}
  const code = dictForm.value.code.trim()
  const name = dictForm.value.name.trim()
  if (!code) next.code = '请填写类型编码'
  else if (code.length < 2 || code.length > 50) next.code = '类型编码为 2～50 位'
  else if (dicts.value.some((d) => d.id !== editingDictId.value && d.code === code)) {
    next.code = '类型编码已存在'
  }
  if (!name) next.name = '请填写类型名称'
  else if (name.length > 30) next.name = '类型名称为 1～30 字符'
  dictErrors.value = next
  dictFormError.value = ''
  if (Object.keys(next).length) return

  dictSaving.value = true
  try {
    await saveDict(
      {
        code,
        name,
        remark: dictForm.value.remark.trim() || undefined,
      },
      editingDictId.value ?? undefined,
    )
    ElMessage.success(editingDictId.value ? '已保存' : '已创建')
    dictDialog.value = false
    await loadDicts()
  } catch (e: unknown) {
    dictFormError.value = readErrMsg(e) || '保存失败，请稍后重试'
  } finally {
    dictSaving.value = false
  }
}

/* ===== 字典类型：删除（危险确认）===== */
function openDeleteDict(row: DictRow) {
  dictDelTarget.value = row
  dictDelError.value = ''
  dictDelDialog.value = true
}

async function confirmDeleteDict() {
  const row = dictDelTarget.value
  if (!row) return
  dictDeleting.value = true
  dictDelError.value = ''
  try {
    await deleteDict(row.id)
    ElMessage.success('已删除')
    dictDelDialog.value = false
    if (selectedId.value === row.id) selectedId.value = null
    await loadDicts()
  } catch (e: unknown) {
    // 弹窗不关：把后端 msg 内联显示，不静默
    dictDelError.value = readErrMsg(e) || '删除失败，请稍后重试'
  } finally {
    dictDeleting.value = false
  }
}

/* ===== 字典项：新增 / 编辑 ===== */
function openCreateItem() {
  if (!selected.value) {
    ElMessage.warning('请先选择左侧字典')
    return
  }
  editingItemId.value = null
  itemFormError.value = ''
  itemErrors.value = {}
  itemForm.value = {
    label: '',
    value: '',
    sort: items.value.length + 1,
    status: 'ENABLED',
  }
  itemDialog.value = true
}

function openEditItem(row: ItemRow) {
  editingItemId.value = row.id
  itemFormError.value = ''
  itemErrors.value = {}
  itemForm.value = {
    label: row.label,
    value: row.value,
    sort: row.sort,
    status: row.status || 'ENABLED',
  }
  itemDialog.value = true
}

async function submitItem() {
  if (!selected.value) return
  const next: { label?: string; value?: string; sort?: string } = {}
  const label = itemForm.value.label.trim()
  const value = itemForm.value.value.trim()
  if (!label) next.label = '请填写显示文案'
  else if (label.length > 50) next.label = '显示文案为 1～50 字符'
  if (!value) next.value = '请填写存储值'
  else if (value.length > 50) next.value = '存储值为 1～50 字符'
  else if (items.value.some((i) => i.id !== editingItemId.value && i.value === value)) {
    next.value = '同一字典内已存在该存储值'
  }
  const sort = Number(itemForm.value.sort)
  if (itemForm.value.sort !== '' && (Number.isNaN(sort) || !Number.isInteger(sort) || sort < 0)) {
    next.sort = '排序为不小于 0 的整数'
  }
  itemErrors.value = next
  itemFormError.value = ''
  if (Object.keys(next).length) return

  itemSaving.value = true
  try {
    await saveDictItem(
      {
        dictId: selected.value.id,
        label,
        value,
        sort: Number(itemForm.value.sort) || 0,
        status: itemForm.value.status,
      },
      editingItemId.value ?? undefined,
    )
    ElMessage.success(editingItemId.value ? '已保存' : '已创建')
    itemDialog.value = false
    await loadItems()
  } catch (e: unknown) {
    itemFormError.value = readErrMsg(e) || '保存失败，请稍后重试'
  } finally {
    itemSaving.value = false
  }
}

/* ===== 字典项：删除（危险确认 · D11-03）===== */
function openDeleteItem(row: ItemRow) {
  itemDelTarget.value = row
  itemDelError.value = ''
  itemDelDialog.value = true
}

async function confirmDeleteItem() {
  const row = itemDelTarget.value
  if (!row) return
  itemDeleting.value = true
  itemDelError.value = ''
  try {
    await deleteDictItem(row.id)
    ElMessage.success('已删除')
    itemDelDialog.value = false
    await loadItems()
  } catch (e: unknown) {
    // 弹窗不关：把后端 msg 内联显示，不静默
    itemDelError.value = readErrMsg(e) || '删除失败，请稍后重试'
  } finally {
    itemDeleting.value = false
  }
}

onMounted(loadDicts)
</script>

<template>
  <PageHead title="字典管理" sub="配置业务码与显示文案 · 供筛选、下拉、状态标签复用">
    <template #actions>
      <button class="btn btn-primary" type="button" @click="openCreateDict">+ 新增字典</button>
    </template>
  </PageHead>

  <div class="hint-banner">
    <b>怎么用：</b>
    左侧选字典类型，右侧维护选项。
    <span class="code">value</span> 是存储值（如 <span class="code">PENDING_PAY</span>），
    <span class="code">label</span> 是显示文案（如「待付款」）。
  </div>

  <div class="split dict-layout">
    <div class="card card-pad dict-list">
      <div class="nav-group-title nav-title">字典类型</div>
      <input v-model="keyword" class="input dict-search" type="search" placeholder="搜索编码 / 名称" />
      <table class="table">
        <thead>
          <tr>
            <th>字典</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!filteredDicts.length">
            <td colspan="2" class="hint empty-cell-sm">暂无字典</td>
          </tr>
          <tr
            v-for="row in filteredDicts"
            :key="row.id"
            class="dict-row"
            :class="{ active: selectedId === row.id }"
            @click="selectedId = row.id"
          >
            <td>
              <b v-if="selectedId === row.id">{{ row.name }}</b>
              <template v-else>{{ row.name }}</template>
              <div class="hint code dict-code">{{ row.code }}</div>
              <div v-if="row.remark" class="hint dict-remark">{{ row.remark }}</div>
            </td>
            <td class="col-op ops-nowrap">
              <button class="btn btn-ghost btn-sm" type="button" @click.stop="openEditDict(row)">
                编辑
              </button>
              <button class="btn btn-ghost btn-sm" type="button" @click.stop="openDeleteDict(row)">
                删除
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="card card-pad dict-items">
      <div class="panel-head">
        <div>
          <div class="page-title items-title">
            字典选项
            <template v-if="selected">· {{ selected.name }}</template>
          </div>
          <div v-if="selected" class="hint items-sub">
            编码 <span class="code">{{ selected.code }}</span> · 共 {{ items.length }} 项
          </div>
          <div v-else class="hint items-sub">请先在左侧选择一个字典</div>
        </div>
        <button
          class="btn btn-primary btn-sm"
          type="button"
          :disabled="!selected"
          @click="openCreateItem"
        >
          + 新增选项
        </button>
      </div>

      <table class="table">
        <thead>
          <tr>
            <th>显示文案 (label)</th>
            <th class="code">存储值 (value)</th>
            <th class="num center">排序</th>
            <th>状态</th>
            <th class="col-op">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!selected">
            <td colspan="5" class="hint empty-cell">未选择字典</td>
          </tr>
          <tr v-else-if="itemsLoading">
            <td colspan="5" class="hint empty-cell">加载中…</td>
          </tr>
          <tr v-else-if="!items.length">
            <td colspan="5" class="hint empty-cell">暂无选项，点击右上角「新增选项」添加</td>
          </tr>
          <tr v-for="row in items" :key="row.id">
            <td>{{ row.label }}</td>
            <td class="code">{{ row.value }}</td>
            <td class="num center">{{ row.sort }}</td>
            <td>
              <StatusBadge
                :text="row.status === 'ENABLED' ? '启用' : '禁用'"
                :tone="row.status === 'ENABLED' ? 'forest' : 'brick'"
              />
            </td>
            <td class="col-op">
              <button class="btn btn-ghost btn-sm" type="button" @click="openEditItem(row)">
                编辑
              </button>
              <button class="btn btn-ghost btn-sm" type="button" @click="openDeleteItem(row)">
                删除
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <!-- 新增 / 编辑字典类型（dlg-dict-type-form · D11-01） -->
  <FormDialog
    v-model="dictDialog"
    :title="editingDictId ? '编辑字典类型' : '新增字典类型'"
    icon="info"
    icon-text="✎"
    width="is-md"
    cancel-text="取消"
    confirm-text="保存"
    :loading="dictSaving"
    @submit="submitDict"
  >
    <p v-if="dictFormError" class="error-text">{{ dictFormError }}</p>
    <div class="field">
      <label class="label">类型编码<span class="req">*</span></label>
      <input v-model="dictForm.code" class="input" maxlength="50" />
      <span class="hint">
        2～50 位，全局唯一（如 <span class="code">order_status</span>）；代码按编码读取，改名会导致取不到值
      </span>
      <span v-if="dictErrors.code" class="error-text">{{ dictErrors.code }}</span>
    </div>
    <div class="field">
      <label class="label">类型名称<span class="req">*</span></label>
      <input v-model="dictForm.name" class="input" maxlength="30" />
      <span class="hint">1～30 字符，仅用于后台展示</span>
      <span v-if="dictErrors.name" class="error-text">{{ dictErrors.name }}</span>
    </div>
    <div class="field">
      <label class="label">备注</label>
      <input v-model="dictForm.remark" class="input" />
    </div>
    <div class="alert alert-info">编码重复时提交会被拒绝并提示「类型编码已存在」；删除字典类型需先清空其选项。</div>
  </FormDialog>

  <!-- 新增 / 编辑字典项（dlg-dict-item-form · D11-02） -->
  <FormDialog
    v-model="itemDialog"
    :title="editingItemId ? '编辑字典项' : '新增字典项'"
    icon="info"
    icon-text="✎"
    width="is-md"
    cancel-text="取消"
    confirm-text="保存"
    :loading="itemSaving"
    @submit="submitItem"
  >
    <p v-if="itemFormError" class="error-text">{{ itemFormError }}</p>
    <div class="field">
      <label class="label">所属字典</label>
      <input
        class="input"
        :value="selected ? `${selected.name}（${selected.code}）` : ''"
        readonly
      />
    </div>
    <div class="form-grid">
      <div class="field">
        <label class="label">显示文案<span class="req">*</span></label>
        <input v-model="itemForm.label" class="input" maxlength="50" />
        <span class="hint">1～50 字符，列表与筛选展示用</span>
        <span v-if="itemErrors.label" class="error-text">{{ itemErrors.label }}</span>
      </div>
      <div class="field">
        <label class="label">存储值<span class="req">*</span></label>
        <input v-model="itemForm.value" class="input" maxlength="50" />
        <span class="hint">1～50 字符，同一字典内唯一</span>
        <span v-if="itemErrors.value" class="error-text">{{ itemErrors.value }}</span>
      </div>
      <div class="field">
        <label class="label">排序</label>
        <input v-model.number="itemForm.sort" class="input" type="number" min="0" />
        <span class="hint">同级从小到大</span>
        <span v-if="itemErrors.sort" class="error-text">{{ itemErrors.sort }}</span>
      </div>
      <div class="field">
        <label class="label">状态</label>
        <select v-model="itemForm.status" class="select">
          <option value="ENABLED">启用</option>
          <option value="DISABLED">禁用</option>
        </select>
      </div>
    </div>
    <div class="alert alert-info">存储值一旦被业务数据引用请勿修改（订单、库存等按值存储）；确需变更时建议新增选项并把旧项置为<b>禁用</b>。</div>
  </FormDialog>

  <!-- 删除字典项（dlg-dict-item-del · D11-03） -->
  <FormDialog
    v-model="itemDelDialog"
    title="删除字典项"
    icon="danger"
    width="is-sm"
    cancel-text="取消"
    confirm-text="确认删除"
    :loading="itemDeleting"
    @submit="confirmDeleteItem"
  >
    <p v-if="itemDelError" class="error-text">{{ itemDelError }}</p>
    <p>确认删除字典项「{{ itemDelTarget?.label }}（{{ itemDelTarget?.value }}）」？</p>
    <p class="hint">
      删除后所有按该值渲染的状态标签会<b>显示为原始存储值</b>，且不可恢复；仍被业务数据引用时删除会被拒绝。
    </p>
  </FormDialog>

  <!-- 删除字典类型（dlg-dict-del） -->
  <FormDialog
    v-model="dictDelDialog"
    title="删除字典类型"
    icon="danger"
    width="is-sm"
    cancel-text="取消"
    confirm-text="确认删除"
    :loading="dictDeleting"
    @submit="confirmDeleteDict"
  >
    <p v-if="dictDelError" class="error-text">{{ dictDelError }}</p>
    <p>确认删除字典「{{ dictDelTarget?.name }}（{{ dictDelTarget?.code }}）」？</p>
    <p class="hint">该字典下的全部选项会一并删除，且不可恢复；仍被业务数据引用时删除会被拒绝。</p>
  </FormDialog>
</template>

<style scoped>
/* 原型 dicts.html 内联样式的类化版本（不写行内 style） */
.hint-banner {
  margin-bottom: 14px;
  padding: 12px 14px;
  border-radius: 8px;
  background: var(--sand, #f3efe8);
  color: var(--ink, #1a1a1a);
  font-size: 13px;
  line-height: 1.55;
}
.hint-banner .code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}
.dict-layout {
  align-items: stretch;
}
.dict-list,
.dict-items {
  min-width: 0;
}
.nav-title {
  padding: 0;
}
.dict-search {
  margin-bottom: 10px;
}
.dict-row {
  cursor: pointer;
}
.dict-row.active {
  background: var(--sand);
}
.dict-code,
.dict-remark {
  font-size: 11px;
  margin-top: 2px;
}
.ops-nowrap {
  white-space: nowrap;
}
.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.items-title {
  font-size: 16px;
  margin: 0;
}
.items-sub {
  margin-top: 4px;
}
.empty-cell {
  text-align: center;
  padding: 24px;
}
.empty-cell-sm {
  text-align: center;
  padding: 16px;
}
</style>