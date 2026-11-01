<script setup lang="ts">
/**
 * 角色管理 —— 对齐原型 .stitch/原型/admin/roles.html 与图鉴 dialogs.html 第 8 组。
 * 弹窗：dlg-role-form（D8-01 新增/编辑）、dlg-role-del（D8-02 删除）、
 *       dlg-role-inuse（D8-03 被用户引用）、dlg-role-builtin（D8-04 内置角色不可删除）。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import PageHead from '@/components/PageHead.vue'
import FormDialog from '@/components/FormDialog.vue'
import NoticeDialog from '@/components/NoticeDialog.vue'
import { fetchPermissionTree } from '@/api/menus'
import { deleteRole, fetchRoles, saveRole } from '@/api/roles'
import { fetchUsers } from '@/api/users'
import { unwrapList, unwrapPage } from '@/utils/page'

type RoleRow = {
  id: string
  name: string
  code: string
  users: number
  dataScope: string
  status: string
  remark: string
  permissionIds: string[]
}

type PermNode = {
  id: string
  name: string
  code: string
  type: string
  children: PermNode[]
}

type FlatRow = PermNode & { depth: number }

const SCOPE_LABELS: Record<string, string> = {
  ALL: '全部数据',
  DEPT: '本部门及下级部门',
  SELF: '仅本人',
}

const roles = ref<RoleRow[]>([])
const selectedRoleId = ref<string | null>(null)
const seg = ref<'perm' | 'scope'>('perm')
const checked = ref<Set<string>>(new Set())
const permTree = ref<PermNode[]>([])
const expanded = ref<Set<string>>(new Set())
const keyword = ref('')

/* ===== 新增 / 编辑角色（D8-01）===== */
const formDialog = ref(false)
const editingId = ref<string | null>(null)
const formSaving = ref(false)
const formError = ref('')
const form = ref({ code: '', name: '', dataScope: 'ALL' })
const formErrors = ref<{ code?: string; name?: string }>({})

/* ===== 删除角色（D8-02）===== */
const delDialog = ref(false)
const delTarget = ref<RoleRow | null>(null)
const delSaving = ref(false)
const delError = ref('')

/* ===== 拒绝（D8-03 被引用 / D8-04 内置）===== */
const rejectDialog = ref(false)
const rejectKind = ref<'inUse' | 'builtin'>('inUse')
const rejectRole = ref<RoleRow | null>(null)
const rejectUserNames = ref<string[]>([])
const rejectMsg = ref('')

const selectedRole = computed(
  () => roles.value.find((r) => r.id === selectedRoleId.value) ?? null,
)

const scopeLabel = computed(() => {
  const scope = selectedRole.value?.dataScope || 'ALL'
  return SCOPE_LABELS[scope] ?? scope
})

/** 拒绝文案里的「（林晓涵、胡健）」，取不到引用人就只留引用数 */
const rejectNames = computed(() =>
  rejectUserNames.value.length ? `（${rejectUserNames.value.join('、')}）` : '',
)

function typeLabel(type: string) {
  const t = type.toUpperCase()
  if (t === 'DIR') return '目录'
  if (t === 'MENU') return '菜单'
  if (t === 'BUTTON') return '按钮'
  if (t === 'API') return '接口'
  return type || ''
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

function mapTreeOrdered(nodes: Record<string, unknown>[]): PermNode[] {
  return nodes.map((n) => ({
    id: String(n.id ?? ''),
    name: String(n.name ?? n.title ?? ''),
    code: String(n.code ?? ''),
    type: String(n.type ?? ''),
    children: Array.isArray(n.children)
      ? mapTreeOrdered(n.children as Record<string, unknown>[])
      : [],
  }))
}

function collectExpandableIds(nodes: PermNode[]): string[] {
  const ids: string[] = []
  const walk = (list: PermNode[]) => {
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

/** 默认只展开顶层有子节点的目录/菜单，避免一进来铺满按钮 */
function defaultExpandedIds(nodes: PermNode[]): Set<string> {
  return new Set(nodes.filter((n) => n.children.length).map((n) => n.id))
}

function collectAllIds(nodes: PermNode[]): string[] {
  const ids: string[] = []
  const walk = (list: PermNode[]) => {
    for (const n of list) {
      ids.push(n.id)
      if (n.children.length) walk(n.children)
    }
  }
  walk(nodes)
  return ids
}

function nodeMatches(node: PermNode, kw: string): boolean {
  if (!node || !kw) return true
  const q = kw.toLowerCase()
  if (node.name.toLowerCase().includes(q) || node.code.toLowerCase().includes(q)) return true
  return node.children.some((c) => nodeMatches(c, kw))
}

function filterTree(nodes: PermNode[], kw: string): PermNode[] {
  if (!kw.trim()) return nodes
  const q = kw.trim()
  const walk = (list: PermNode[]): PermNode[] =>
    list
      .map((n) => {
        const children = walk(n.children)
        if (nodeMatches(n, q) || children.length) {
          return { ...n, children }
        }
        return null
      })
      .filter(Boolean) as PermNode[]
  return walk(nodes)
}

const filteredTree = computed(() => filterTree(permTree.value, keyword.value))

const visibleRows = computed(() => {
  const out: FlatRow[] = []
  const walk = (list: PermNode[], depth: number) => {
    for (const n of list) {
      out.push({ ...n, depth })
      if (n.children.length && expanded.value.has(n.id)) {
        walk(n.children, depth + 1)
      }
    }
  }
  walk(filteredTree.value, 0)
  return out
})

const totalPermCount = computed(() => collectAllIds(permTree.value).length)
const checkedCount = computed(() => {
  const all = new Set(collectAllIds(permTree.value))
  let n = 0
  for (const id of checked.value) {
    if (all.has(id)) n++
  }
  return n
})

async function loadRoles() {
  try {
    const res = await fetchRoles({ page: 1, pageSize: 100 })
    const pageData = unwrapPage<Record<string, unknown>>(res.data)
    const list = pageData?.records ?? unwrapList<Record<string, unknown>>(res.data)
    const prev = selectedRoleId.value
    roles.value = list.map((r) => ({
      id: String(r.id ?? ''),
      name: String(r.name ?? r.code ?? '—'),
      code: String(r.code ?? ''),
      users: Number(r.userCount ?? r.users ?? 0),
      dataScope: String(r.dataScope ?? 'ALL'),
      status: String(r.status ?? 'ENABLED'),
      remark: String(r.remark ?? ''),
      permissionIds: Array.isArray(r.permissionIds)
        ? (r.permissionIds as Array<string | number>).map(String)
        : [],
    }))
    if (roles.value.length) {
      const still = prev && roles.value.some((r) => r.id === prev)
      selectedRoleId.value = still ? prev : roles.value[0]!.id
    }
  } catch {
    roles.value = []
  }
}

async function loadPermTree() {
  try {
    const res = await fetchPermissionTree()
    permTree.value = mapTreeOrdered(unwrapList<Record<string, unknown>>(res.data))
    expanded.value = defaultExpandedIds(permTree.value)
  } catch {
    permTree.value = []
  }
}

/** 拒绝文案里的引用人姓名（best-effort，取不到就只显示引用数） */
async function loadRoleUserNames(roleId: string): Promise<string[]> {
  try {
    const res = await fetchUsers({ page: 1, pageSize: 200 })
    const pageData = unwrapPage<Record<string, unknown>>(res.data)
    return (pageData?.records ?? [])
      .filter((u) => {
        const ids = Array.isArray(u.roleIds) ? (u.roleIds as unknown[]).map(String) : []
        return ids.includes(roleId)
      })
      .map((u) => String(u.realName ?? u.username ?? ''))
      .filter(Boolean)
  } catch {
    return []
  }
}

watch(selectedRoleId, (id) => {
  const role = roles.value.find((r) => r.id === id)
  checked.value = new Set(role?.permissionIds ?? [])
})

watch(keyword, (kw) => {
  if (!kw.trim()) return
  // 搜索时自动展开匹配路径
  const ids = collectExpandableIds(filteredTree.value)
  expanded.value = new Set([...expanded.value, ...ids])
})

function toggleExpand(id: string) {
  const next = new Set(expanded.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expanded.value = next
}

function expandAll() {
  expanded.value = new Set(collectExpandableIds(filteredTree.value))
}

function collapseAll() {
  expanded.value = new Set()
}

function collectDescendantIds(node: PermNode): string[] {
  const ids = [node.id]
  for (const c of node.children) {
    ids.push(...collectDescendantIds(c))
  }
  return ids
}

function findNode(nodes: PermNode[], id: string): PermNode | null {
  for (const n of nodes) {
    if (n.id === id) return n
    const hit = findNode(n.children, id)
    if (hit) return hit
  }
  return null
}

function toggleRow(row: FlatRow) {
  const node = findNode(permTree.value, row.id) || row
  const ids = collectDescendantIds(node)
  const next = new Set(checked.value)
  const allChecked = ids.every((id) => next.has(id))
  if (allChecked) {
    for (const id of ids) next.delete(id)
  } else {
    for (const id of ids) next.add(id)
  }
  checked.value = next
}

function isRowChecked(row: FlatRow): boolean {
  const node = findNode(permTree.value, row.id) || row
  return collectDescendantIds(node).every((id) => checked.value.has(id))
}

function isRowIndeterminate(row: FlatRow): boolean {
  const node = findNode(permTree.value, row.id) || row
  const ids = collectDescendantIds(node)
  if (ids.length <= 1) return false
  const n = ids.filter((id) => checked.value.has(id)).length
  return n > 0 && n < ids.length
}

function checkAll() {
  checked.value = new Set(collectAllIds(permTree.value))
}

function uncheckAll() {
  checked.value = new Set()
}

function setScope(value: string) {
  const role = selectedRole.value
  if (role) role.dataScope = value
}

async function savePerms() {
  const role = selectedRole.value
  if (!role) {
    ElMessage.warning('请先选择角色')
    return
  }
  try {
    await saveRole(
      {
        name: role.name,
        dataScope: role.dataScope,
        status: role.status,
        remark: role.remark,
        permissionIds: [...checked.value],
      },
      role.id,
    )
    ElMessage.success('权限已保存')
    await loadRoles()
  } catch (e: unknown) {
    ElMessage.error(readErrMsg(e) || '权限保存失败，请稍后重试')
  }
}

/* ===== 新增 / 编辑角色 ===== */
function openCreate() {
  editingId.value = null
  formError.value = ''
  formErrors.value = {}
  form.value = { code: '', name: '', dataScope: 'ALL' }
  formDialog.value = true
}

function openEdit(role: RoleRow) {
  editingId.value = role.id
  formError.value = ''
  formErrors.value = {}
  form.value = {
    code: role.code,
    name: role.name,
    dataScope: role.dataScope || 'ALL',
  }
  formDialog.value = true
}

async function submitRole() {
  const errors: typeof formErrors.value = {}
  const code = form.value.code.trim()
  const name = form.value.name.trim()
  if (!code) errors.code = '请填写角色编码'
  else if (!/^[A-Za-z0-9_]{2,30}$/.test(code)) {
    errors.code = '角色编码为 2～30 位，仅字母 / 数字 / 下划线'
  }
  if (!name) errors.name = '请填写角色名称'
  else if (name.length > 30) errors.name = '角色名称为 1～30 字符'
  else if (roles.value.some((r) => r.id !== editingId.value && r.name === name)) {
    errors.name = '角色名称不能与已有角色重名'
  }
  formErrors.value = errors
  formError.value = ''
  if (Object.keys(errors).length) return

  const editing = roles.value.find((r) => r.id === editingId.value)
  formSaving.value = true
  try {
    await saveRole(
      {
        code: editingId.value ? undefined : code,
        name,
        dataScope: form.value.dataScope,
        status: editing?.status ?? 'ENABLED',
        remark: editing?.remark ?? '',
        permissionIds: editing ? editing.permissionIds.map(Number) : [],
      },
      editingId.value ?? undefined,
    )
    ElMessage.success(editingId.value ? '已保存' : '角色已创建')
    formDialog.value = false
    await loadRoles()
  } catch (e: unknown) {
    formError.value = readErrMsg(e) || '保存失败，请稍后重试'
  } finally {
    formSaving.value = false
  }
}

/* ===== 删除角色（D8-02 / D8-03 / D8-04）===== */
function isBuiltin(role: RoleRow): boolean {
  return role.id === '1' || role.code === 'SUPER_ADMIN'
}

async function requestDelete(role: RoleRow) {
  if (isBuiltin(role)) {
    rejectKind.value = 'builtin'
    rejectRole.value = role
    rejectUserNames.value = []
    rejectMsg.value = ''
    rejectDialog.value = true
    return
  }
  if (role.users > 0) {
    rejectKind.value = 'inUse'
    rejectRole.value = role
    rejectUserNames.value = []
    rejectMsg.value = ''
    rejectDialog.value = true
    rejectUserNames.value = await loadRoleUserNames(role.id)
    return
  }
  delTarget.value = role
  delError.value = ''
  delDialog.value = true
}

async function confirmDelete() {
  const role = delTarget.value
  if (!role) return
  delSaving.value = true
  delError.value = ''
  try {
    await deleteRole(role.id)
    ElMessage.success('已删除')
    delDialog.value = false
    if (selectedRoleId.value === role.id) selectedRoleId.value = null
    await loadRoles()
  } catch (e: unknown) {
    const msg = readErrMsg(e)
    delDialog.value = false
    // 后端同样会拦内置角色 / 被引用角色：翻成图鉴里的拒绝弹窗，而不是一条通用 toast
    rejectKind.value = isBuiltin(role) || msg.includes('超管') ? 'builtin' : 'inUse'
    rejectRole.value = role
    rejectUserNames.value = []
    rejectMsg.value = msg
    rejectDialog.value = true
    if (rejectKind.value === 'inUse') {
      rejectUserNames.value = await loadRoleUserNames(role.id)
    }
  } finally {
    delSaving.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadRoles(), loadPermTree()])
  if (selectedRole.value) {
    checked.value = new Set(selectedRole.value.permissionIds)
  }
})
</script>

<template>
  <PageHead title="角色管理" sub="角色 CRUD · 权限绑定 · 数据范围 ALL / DEPT / SELF">
    <template #actions>
      <button class="btn btn-primary" type="button" @click="openCreate">+ 新增角色</button>
    </template>
  </PageHead>

  <div class="split roles-layout">
    <div class="card card-pad roles-list">
      <div class="nav-group-title">角色列表</div>
      <table class="table">
        <thead>
          <tr>
            <th>角色 / 编码</th>
            <th>数据范围</th>
            <th class="num">用户</th>
            <th class="col-op">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!roles.length">
            <td colspan="4" class="hint empty-cell">暂无角色</td>
          </tr>
          <tr
            v-for="role in roles"
            :key="role.id"
            :class="{ 'row-active': selectedRoleId === role.id }"
            @click="selectedRoleId = role.id"
          >
            <td>
              <b>{{ role.name }}</b>
              <span v-if="isBuiltin(role)" class="badge badge-amber">内置</span>
              <div class="hint code role-code">{{ role.code }}</div>
            </td>
            <td class="code">{{ role.dataScope }}</td>
            <td class="num">{{ role.users }}</td>
            <td class="col-op">
              <button class="btn btn-ghost btn-sm" type="button" @click.stop="openEdit(role)">
                编辑
              </button>
              <button class="btn btn-ghost btn-sm" type="button" @click.stop="requestDelete(role)">
                删除
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="card card-pad roles-perm">
      <div class="page-head panel-head">
        <div class="page-title panel-title">
          权限分配 · {{ selectedRole?.name || '未选择' }}
        </div>
        <div class="seg">
          <span class="seg-item" :class="{ active: seg === 'perm' }" @click="seg = 'perm'"
            >功能权限</span
          >
          <span class="seg-item" :class="{ active: seg === 'scope' }" @click="seg = 'scope'"
            >数据范围</span
          >
        </div>
      </div>

      <template v-if="seg === 'perm'">
        <div class="perm-toolbar">
          <input
            v-model="keyword"
            class="input"
            type="search"
            placeholder="筛选名称 / 权限码"
          />
          <div class="perm-toolbar-actions">
            <button class="btn btn-secondary btn-sm" type="button" @click="expandAll">
              全部展开
            </button>
            <button class="btn btn-secondary btn-sm" type="button" @click="collapseAll">
              全部收起
            </button>
            <button class="btn btn-ghost btn-sm" type="button" @click="checkAll">全选</button>
            <button class="btn btn-ghost btn-sm" type="button" @click="uncheckAll">清空</button>
          </div>
        </div>
        <div class="perm-meta hint">
          已选 {{ checkedCount }} / {{ totalPermCount }} · 数据范围
          {{ selectedRole?.dataScope || 'ALL' }}（{{ scopeLabel }}）
          <span v-if="keyword.trim()"> · 筛选中</span>
        </div>

        <div v-if="visibleRows.length" class="perm-tree-scroll">
          <div
            v-for="row in visibleRows"
            :key="row.id"
            class="tree-item perm-row"
            :class="`depth-${Math.min(row.depth, 3)}`"
          >
            <button
              v-if="row.children.length"
              class="chev-btn"
              type="button"
              @click.stop="toggleExpand(row.id)"
            >
              {{ expanded.has(row.id) ? '▾' : '▸' }}
            </button>
            <span v-else class="chev-btn chev-btn--leaf" aria-hidden="true" />
            <input
              type="checkbox"
              :checked="isRowChecked(row)"
              :indeterminate="isRowIndeterminate(row)"
              @change="toggleRow(row)"
            />
            <span class="perm-name">{{ row.name }}</span>
            <span v-if="row.code" class="hint code-tag">{{ row.code }}</span>
            <span v-if="row.type" class="type-tag">{{ typeLabel(row.type) }}</span>
          </div>
        </div>
        <p v-else class="hint">{{ keyword.trim() ? '无匹配权限' : '暂无权限树' }}</p>
      </template>

      <div v-else>
        <select
          class="select scope-select"
          :value="selectedRole?.dataScope || 'ALL'"
          @change="setScope(($event.target as HTMLSelectElement).value)"
        >
          <option value="ALL">ALL · 全部数据</option>
          <option value="DEPT">DEPT · 本部门及下级部门</option>
          <option value="SELF">SELF · 仅本人</option>
        </select>
        <p class="hint scope-hint">
          决定该角色在列表页可见的数据范围（ALL / DEPT / SELF）。
        </p>
      </div>

      <div class="perm-actions">
        <button class="btn btn-primary btn-sm" type="button" @click="savePerms">保存权限</button>
        <button
          class="btn btn-secondary btn-sm"
          type="button"
          @click="checked = new Set(selectedRole?.permissionIds ?? [])"
        >
          重置
        </button>
      </div>
    </div>
  </div>

  <!-- 新增 / 编辑角色（D8-01 · dlg-role-form） -->
  <FormDialog
    v-model="formDialog"
    :title="editingId ? '编辑角色' : '新增角色'"
    icon="info"
    icon-text="✎"
    width="is-md"
    cancel-text="取消"
    confirm-text="保存"
    :loading="formSaving"
    @submit="submitRole"
  >
    <p v-if="formError" class="error-text">{{ formError }}</p>
    <div class="field">
      <label class="label">角色编码<span class="req">*</span></label>
      <input
        v-model="form.code"
        class="input"
        :class="{ 'input-readonly': !!editingId }"
        :readonly="!!editingId"
        maxlength="30"
      />
      <span class="hint">
        2～30 位，仅字母 / 数字 / 下划线；<b>创建后不可修改</b>（权限与用户绑定按编码记录）
      </span>
      <span v-if="formErrors.code" class="error-text">{{ formErrors.code }}</span>
    </div>
    <div class="field">
      <label class="label">角色名称<span class="req">*</span></label>
      <input v-model="form.name" class="input" maxlength="30" />
      <span class="hint">1～30 字符，不能与已有角色重名</span>
      <span v-if="formErrors.name" class="error-text">{{ formErrors.name }}</span>
    </div>
    <div class="field">
      <label class="label">数据范围<span class="req">*</span></label>
      <select v-model="form.dataScope" class="select">
        <option value="ALL">ALL · 全部数据</option>
        <option value="DEPT">DEPT · 本部门及下级部门</option>
        <option value="SELF">SELF · 仅本人</option>
      </select>
      <span class="hint">决定该角色在列表页可见的数据范围（ALL / DEPT / SELF）</span>
    </div>
    <div class="alert alert-info">
      角色创建后可在右侧权限树继续分配功能权限；「超级管理员」为内置角色，不可删除。
    </div>
  </FormDialog>

  <!-- 删除角色（D8-02 · dlg-role-del） -->
  <FormDialog
    v-model="delDialog"
    title="删除角色"
    icon="danger"
    width="is-sm"
    cancel-text="取消"
    confirm-text="确认删除"
    :loading="delSaving"
    @submit="confirmDelete"
  >
    <p v-if="delError" class="error-text">{{ delError }}</p>
    <p>确认删除角色「{{ delTarget?.name }}（{{ delTarget?.code }}）」？</p>
    <p class="hint">
      该角色当前<b>没有用户</b>，删除后其权限绑定一并解除，不可恢复。
    </p>
  </FormDialog>

  <!-- 角色被用户引用（D8-03 · dlg-role-inuse）/ 内置角色不可删除（D8-04 · dlg-role-builtin） -->
  <NoticeDialog
    v-model="rejectDialog"
    title="无法删除角色"
    icon="warn"
    width="is-sm"
    confirm-text="知道了"
  >
    <template v-if="rejectKind === 'inUse'">
      <p>
        角色「{{ rejectRole?.name }}（{{ rejectRole?.code }}）」仍被
        <b>{{ rejectRole?.users }}</b> 个用户引用{{ rejectNames }}，不能删除。
      </p>
      <p class="hint">请先在「用户管理」中为这些用户更换角色，待引用数归零后再删除。</p>
    </template>
    <template v-else>
      <p>
        「{{ rejectRole?.name }}（{{ rejectRole?.code }}）」是系统<b>内置角色</b>，不允许删除。
      </p>
      <p class="hint">内置角色承载全部权限，删除会导致系统无法管理；其名称与编码也不可修改。</p>
    </template>
    <p v-if="rejectMsg" class="hint">{{ rejectMsg }}</p>
  </NoticeDialog>
</template>

<style scoped>
.roles-layout {
  align-items: stretch;
}
.roles-list {
  min-width: 0;
}
.roles-perm {
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.row-active {
  background: var(--sand);
}
.role-code {
  font-size: 11px;
}
.empty-cell {
  text-align: center;
  padding: 16px;
}
.panel-head {
  margin-bottom: 12px;
}
.panel-title {
  font-size: 16px;
}
.input-readonly {
  background: var(--sand);
  color: var(--stone);
  cursor: not-allowed;
}
.perm-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}
.perm-toolbar .input {
  flex: 1;
  min-width: 160px;
}
.perm-toolbar-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.perm-meta {
  margin-bottom: 8px;
  font-size: 12px;
}
.perm-tree-scroll {
  max-height: min(56vh, 520px);
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--cream, #faf8f5);
  padding: 4px 0;
}
.perm-row {
  cursor: default;
}
.perm-row.depth-0 {
  padding-left: 8px;
}
.perm-row.depth-1 {
  padding-left: 26px;
}
.perm-row.depth-2 {
  padding-left: 44px;
}
.perm-row.depth-3 {
  padding-left: 62px;
}
.chev-btn {
  flex: 0 0 16px;
  width: 16px;
  border: 0;
  background: transparent;
  color: var(--slate);
  cursor: pointer;
  padding: 0;
  line-height: 1;
  font-size: 12px;
}
.chev-btn--leaf {
  visibility: hidden;
  pointer-events: none;
}
.perm-name {
  font-weight: 500;
}
.code-tag {
  font-size: 11px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.type-tag {
  margin-left: auto;
  font-size: 11px;
  color: var(--slate);
  background: rgba(0, 0, 0, 0.04);
  padding: 1px 6px;
  border-radius: 4px;
}
.scope-select {
  width: 220px;
}
.scope-hint {
  margin-top: 8px;
}
.perm-actions {
  margin-top: 16px;
  display: flex;
  gap: 8px;
}
</style>