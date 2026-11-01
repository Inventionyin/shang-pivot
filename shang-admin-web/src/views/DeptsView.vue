<script setup lang="ts">
/**
 * 部门管理 —— 对齐原型 .stitch/原型/admin/depts.html 与弹窗图鉴 dialogs.html。
 * 弹窗：dlg-dept-form（新增/编辑部门 · D10-01）、dlg-dept-del（删除确认 · D10-02）、
 *       dlg-dept-inuse（被用户引用拒绝 · D10-03）、dlg-dept-haschild（有下级拒绝 · D10-04）。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHead from '@/components/PageHead.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import FormDialog from '@/components/FormDialog.vue'
import NoticeDialog from '@/components/NoticeDialog.vue'
import { deleteDept, fetchDeptTree, saveDept } from '@/api/depts'
import { fetchUsers } from '@/api/users'
import { unwrapList, unwrapPage } from '@/utils/page'

type DeptNode = {
  id: string
  parentId: string
  name: string
  sort: number
  status: string
  children: DeptNode[]
  depth: number
}

type RejectMode = 'user' | 'child'

const tree = ref<DeptNode[]>([])
const expanded = ref<Set<string>>(new Set())
const keyword = ref('')
const statusFilter = ref('')

/* ===== 新增 / 编辑部门（dlg-dept-form）===== */
const dialog = ref(false)
const editingId = ref<string | null>(null)
const saving = ref(false)
const formError = ref('')
const form = ref<{ name: string; parentId: string; sort: number | string; status: string }>({
  name: '',
  parentId: '0',
  sort: 0,
  status: 'ENABLED',
})
const errors = ref<{ name?: string; parentId?: string; sort?: string }>({})

/* ===== 删除部门（dlg-dept-del）===== */
const delDialog = ref(false)
const delTarget = ref<DeptNode | null>(null)
const deleting = ref(false)

/* ===== 拒绝删除（dlg-dept-inuse / dlg-dept-haschild）===== */
const rejectDialog = ref(false)
const reject = ref({
  mode: 'user' as RejectMode,
  name: '',
  users: [] as string[],
  children: [] as string[],
  msg: '',
})

const rejectUserText = computed(() => reject.value.users.join('、'))

function mapTree(nodes: Record<string, unknown>[], depth = 0): DeptNode[] {
  return nodes
    .map((n) => {
      const children = Array.isArray(n.children)
        ? mapTree(n.children as Record<string, unknown>[], depth + 1)
        : []
      return {
        id: String(n.id ?? ''),
        parentId: String(n.parentId ?? 0),
        name: String(n.name ?? ''),
        sort: Number(n.sort ?? 0),
        status: String(n.status ?? 'ENABLED'),
        children,
        depth,
      }
    })
    .sort((a, b) => a.sort - b.sort)
}

function collectExpandableIds(nodes: DeptNode[]): string[] {
  const ids: string[] = []
  const walk = (list: DeptNode[]) => {
    for (const n of list) {
      if (n.children.length) {
        ids.push(n.id)
        walk(n.children)
      }
    }
  }
  walk(nodes)
  return ids
}

function flattenVisible(nodes: DeptNode[], expandedIds: Set<string>): DeptNode[] {
  const out: DeptNode[] = []
  const walk = (list: DeptNode[]) => {
    for (const n of list) {
      out.push(n)
      if (n.children.length && expandedIds.has(n.id)) walk(n.children)
    }
  }
  walk(nodes)
  return out
}

function flattenAll(nodes: DeptNode[]): DeptNode[] {
  const out: DeptNode[] = []
  const walk = (list: DeptNode[]) => {
    for (const n of list) {
      out.push(n)
      walk(n.children)
    }
  }
  walk(nodes)
  return out
}

function findName(nodes: DeptNode[], id: string): string | null {
  for (const n of nodes) {
    if (n.id === id) return n.name
    const hit = findName(n.children, id)
    if (hit) return hit
  }
  return null
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

const visibleRows = computed(() => {
  let rows = flattenVisible(tree.value, expanded.value)
  if (statusFilter.value) {
    rows = rows.filter((r) => r.status === statusFilter.value)
  }
  if (keyword.value.trim()) {
    const q = keyword.value.trim().toLowerCase()
    rows = rows.filter((r) => r.name.toLowerCase().includes(q))
  }
  return rows
})

const flatDepts = computed(() => flattenAll(tree.value))

/** 编辑时不能把部门挂到自己或自己的下级下（原型 hint） */
const bannedIds = computed(() => {
  const banned = new Set<string>()
  const id = editingId.value
  if (!id) return banned
  banned.add(id)
  const walk = (nodes: DeptNode[]) => {
    for (const n of nodes) {
      if (banned.has(n.parentId)) banned.add(n.id)
      walk(n.children)
    }
  }
  walk(tree.value)
  return banned
})

/** 上级下拉：顶级为「—（顶级）」，下级用「　└ 」缩进（原型 depts.html） */
const parentOptions = computed(() => {
  const out: Array<{ id: string; label: string }> = [{ id: '0', label: '—（顶级）' }]
  const walk = (nodes: DeptNode[], depth: number) => {
    for (const n of nodes) {
      if (!bannedIds.value.has(n.id)) {
        out.push({
          id: n.id,
          label: depth === 0 ? n.name : `${'　'.repeat(depth)}└ ${n.name}`,
        })
      }
      walk(n.children, depth + 1)
    }
  }
  walk(tree.value, 0)
  return out
})

async function load() {
  try {
    const res = await fetchDeptTree()
    tree.value = mapTree(unwrapList<Record<string, unknown>>(res.data))
    if (expanded.value.size === 0) {
      expanded.value = new Set(collectExpandableIds(tree.value))
    }
  } catch {
    tree.value = []
  }
}

function toggleExpand(id: string) {
  const next = new Set(expanded.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expanded.value = next
}

function expandAll() {
  expanded.value = new Set(collectExpandableIds(tree.value))
}

function collapseAll() {
  expanded.value = new Set()
}

function parentLabel(row: DeptNode): string {
  if (!row.parentId || row.parentId === '0') return '—'
  return findName(tree.value, row.parentId) || row.parentId
}

/* ===== 新增 / 编辑 ===== */
function openCreate(parent?: DeptNode) {
  editingId.value = null
  formError.value = ''
  errors.value = {}
  form.value = {
    name: '',
    parentId: parent?.id ?? '0',
    sort: parent ? parent.children.length + 1 : tree.value.length + 1,
    status: 'ENABLED',
  }
  dialog.value = true
}

function openEdit(row: DeptNode) {
  editingId.value = row.id
  formError.value = ''
  errors.value = {}
  form.value = {
    name: row.name,
    parentId: row.parentId || '0',
    sort: row.sort,
    status: row.status || 'ENABLED',
  }
  dialog.value = true
}

async function submit() {
  const next: { name?: string; parentId?: string; sort?: string } = {}
  const name = form.value.name.trim()
  if (!name) next.name = '请填写部门名称'
  else if (name.length > 30) next.name = '部门名称为 1～30 字符'
  else if (
    flatDepts.value.some(
      (d) => d.id !== editingId.value && d.parentId === form.value.parentId && d.name === name,
    )
  ) {
    next.name = '同一上级下已存在同名部门'
  }
  if (bannedIds.value.has(form.value.parentId)) next.parentId = '上级不能选择自己或自己的下级'
  const sort = Number(form.value.sort)
  if (form.value.sort === '' || Number.isNaN(sort)) next.sort = '请填写排序'
  else if (!Number.isInteger(sort) || sort < 0 || sort > 999) next.sort = '排序为 0～999 的整数'
  errors.value = next
  formError.value = ''
  if (Object.keys(next).length) return

  saving.value = true
  try {
    // 雪花 ID 由后端以字符串下发（Jackson ToStringSerializer），转 Number 会丢精度，原样回传
    const parentId = form.value.parentId || '0'
    await saveDept(
      { name, parentId: parentId === '0' ? 0 : parentId, sort, status: form.value.status },
      editingId.value ?? undefined,
    )
    ElMessage.success(editingId.value ? '已保存' : '已创建')
    dialog.value = false
    await load()
    if (parentId !== '0') {
      expanded.value = new Set([...expanded.value, parentId])
    }
  } catch (e: unknown) {
    formError.value = readErrMsg(e) || '保存失败，请稍后重试'
  } finally {
    saving.value = false
  }
}

/* ===== 删除：先判「有下级 / 被引用」，可删才给危险确认 ===== */
async function loadDeptUsers(id: string): Promise<string[]> {
  try {
    const res = await fetchUsers({ deptId: id, page: 1, pageSize: 50 })
    const page = unwrapPage<Record<string, unknown>>(res.data)
    const list = page?.records ?? unwrapList<Record<string, unknown>>(res.data)
    return list.map((u) => String(u.realName ?? u.username ?? '')).filter(Boolean)
  } catch {
    return []
  }
}

async function openDelete(row: DeptNode) {
  if (row.children.length) {
    reject.value = {
      mode: 'child',
      name: row.name,
      users: [],
      children: row.children.map((c) => c.name),
      msg: '',
    }
    rejectDialog.value = true
    return
  }
  const users = await loadDeptUsers(row.id)
  if (users.length) {
    reject.value = { mode: 'user', name: row.name, users, children: [], msg: '' }
    rejectDialog.value = true
    return
  }
  delTarget.value = row
  delDialog.value = true
}

async function confirmDelete() {
  const row = delTarget.value
  if (!row) return
  deleting.value = true
  try {
    await deleteDept(row.id)
    ElMessage.success('已删除')
    delDialog.value = false
    await load()
  } catch (e: unknown) {
    // 后端拒绝（存在子部门 / 仍有用户引用）：关闭确认框，改弹对应提示并带回后端 msg
    delDialog.value = false
    const users = row.children.length ? [] : await loadDeptUsers(row.id)
    reject.value = {
      mode: row.children.length ? 'child' : 'user',
      name: row.name,
      users,
      children: row.children.map((c) => c.name),
      msg: readErrMsg(e),
    }
    rejectDialog.value = true
  } finally {
    deleting.value = false
  }
}

onMounted(load)
</script>

<template>
  <PageHead title="部门管理" sub="组织架构 · 树形部门 · 有下级或被用户引用时不可删除">
    <template #actions>
      <button class="btn btn-secondary btn-sm" type="button" @click="expandAll">全部展开</button>
      <button class="btn btn-secondary btn-sm" type="button" @click="collapseAll">全部收起</button>
      <button class="btn btn-primary" type="button" @click="openCreate()">+ 新增部门</button>
    </template>
  </PageHead>

  <div class="card card-pad">
    <div class="toolbar">
      <input v-model="keyword" class="input" type="search" placeholder="搜索部门名称" />
      <select v-model="statusFilter" class="select">
        <option value="">全部状态</option>
        <option value="ENABLED">启用</option>
        <option value="DISABLED">禁用</option>
      </select>
      <div class="toolbar-actions">
        <button
          class="btn btn-secondary btn-sm"
          type="button"
          @click="keyword = ''; statusFilter = ''"
        >
          重置
        </button>
      </div>
    </div>

    <table class="table">
      <thead>
        <tr>
          <th class="col-name">名称</th>
          <th>上级</th>
          <th class="num center">排序</th>
          <th>状态</th>
          <th class="col-op">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="!visibleRows.length">
          <td colspan="5" class="hint empty-cell">暂无部门</td>
        </tr>
        <tr v-for="row in visibleRows" :key="row.id">
          <td>
            <div class="tree-name" :class="`depth-${Math.min(row.depth, 3)}`">
              <button
                v-if="row.children.length"
                class="tree-toggle"
                type="button"
                aria-label="展开或收起"
                @click="toggleExpand(row.id)"
              >
                {{ expanded.has(row.id) ? '▾' : '▸' }}
              </button>
              <span v-else class="tree-toggle tree-toggle--leaf" aria-hidden="true" />
              <span class="tree-label">{{ row.name }}</span>
            </div>
          </td>
          <td>{{ parentLabel(row) }}</td>
          <td class="num center">{{ row.sort }}</td>
          <td>
            <StatusBadge
              :text="row.status === 'ENABLED' ? '启用' : '禁用'"
              :tone="row.status === 'ENABLED' ? 'forest' : 'brick'"
            />
          </td>
          <td class="col-op">
            <button class="btn btn-ghost btn-sm" type="button" @click="openCreate(row)">
              下级
            </button>
            <button class="btn btn-ghost btn-sm" type="button" @click="openEdit(row)">编辑</button>
            <button class="btn btn-ghost btn-sm" type="button" @click="openDelete(row)">删除</button>
          </td>
        </tr>
      </tbody>
    </table>
  </div>

  <!-- 新增 / 编辑部门（dlg-dept-form · D10-01） -->
  <FormDialog
    v-model="dialog"
    :title="editingId ? '编辑部门' : '新增部门'"
    icon="info"
    icon-text="✎"
    width="is-md"
    cancel-text="取消"
    confirm-text="保存"
    :loading="saving"
    @submit="submit"
  >
    <p v-if="formError" class="error-text">{{ formError }}</p>
    <div class="field">
      <label class="label">部门名称<span class="req">*</span></label>
      <input v-model="form.name" class="input" maxlength="30" />
      <span class="hint">1～30 字符，同一上级下不能重名</span>
      <span v-if="errors.name" class="error-text">{{ errors.name }}</span>
    </div>
    <div class="field">
      <label class="label">上级部门<span class="req">*</span></label>
      <select v-model="form.parentId" class="select">
        <option v-for="p in parentOptions" :key="p.id" :value="p.id">{{ p.label }}</option>
      </select>
      <span class="hint">顶级部门选「—（顶级）」，同样不能选自己或自己的下级</span>
      <span v-if="errors.parentId" class="error-text">{{ errors.parentId }}</span>
    </div>
    <div class="form-grid">
      <div class="field">
        <label class="label">排序<span class="req">*</span></label>
        <input v-model.number="form.sort" class="input" type="number" min="0" max="999" />
        <span class="hint">0～999，同级越小越靠前</span>
        <span v-if="errors.sort" class="error-text">{{ errors.sort }}</span>
      </div>
      <div class="field">
        <label class="label">状态<span class="req">*</span></label>
        <select v-model="form.status" class="select">
          <option value="ENABLED">启用</option>
          <option value="DISABLED">禁用</option>
        </select>
        <span class="hint">禁用后不再出现在用户归属下拉中</span>
      </div>
    </div>
    <div class="alert alert-info">部门被用户引用或有下级部门时<b>不能删除</b>；如需下线请先迁移用户与下级部门。</div>
  </FormDialog>

  <!-- 删除部门（dlg-dept-del · D10-02） -->
  <FormDialog
    v-model="delDialog"
    title="删除部门"
    icon="danger"
    width="is-sm"
    cancel-text="取消"
    confirm-text="确认删除"
    :loading="deleting"
    @submit="confirmDelete"
  >
    <p>确认删除部门「{{ delTarget?.name }}」？</p>
    <p class="hint">该部门<b>无下级部门、无用户引用</b>，可以删除；删除后不可恢复。</p>
  </FormDialog>

  <!-- 部门被用户引用 / 有下级：拒绝（dlg-dept-inuse · D10-03 / dlg-dept-haschild · D10-04） -->
  <NoticeDialog
    v-model="rejectDialog"
    title="无法删除部门"
    icon="warn"
    width="is-sm"
    confirm-text="知道了"
  >
    <p v-if="reject.mode === 'child'">
      部门「{{ reject.name }}」仍有 <b>{{ reject.children.length }}</b> 个下级部门（{{
        reject.children.join('、')
      }}），不能删除。
    </p>
    <p v-else-if="reject.users.length">
      部门「{{ reject.name }}」仍被 <b>{{ reject.users.length }}</b> 个用户引用（{{
        rejectUserText
      }}），不能删除。
    </p>
    <p v-else>部门「{{ reject.name }}」当前不能删除。</p>
    <p v-if="reject.mode === 'child'" class="hint">请先删除或迁移全部下级部门，再删除该部门。</p>
    <p v-else class="hint">
      请先在「用户管理」中把这些用户改到其他部门，待引用归零后再删除。
    </p>
    <p v-if="reject.msg" class="hint">{{ reject.msg }}</p>
  </NoticeDialog>
</template>

<style scoped>
/* 原型 depts.html 内联样式的类化版本（不写行内 style） */
.col-name {
  min-width: 220px;
}
.tree-name {
  display: flex;
  align-items: center;
  gap: 4px;
  min-height: 22px;
}
.tree-name.depth-1 {
  padding-left: 18px;
}
.tree-name.depth-2 {
  padding-left: 36px;
}
.tree-name.depth-3 {
  padding-left: 54px;
}
.tree-toggle {
  flex: 0 0 18px;
  width: 18px;
  height: 18px;
  border: 0;
  background: transparent;
  color: var(--stone);
  cursor: pointer;
  font-size: 12px;
  line-height: 18px;
  padding: 0;
  text-align: center;
}
.tree-toggle--leaf {
  visibility: hidden;
  pointer-events: none;
  cursor: default;
}
.tree-label {
  font-weight: 500;
  color: var(--ink, #1a1a1a);
}
.empty-cell {
  text-align: center;
  padding: 24px;
}
</style>