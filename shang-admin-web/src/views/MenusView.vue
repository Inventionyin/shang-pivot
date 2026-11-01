<script setup lang="ts">
/**
 * 菜单与权限 —— 对齐原型 .stitch/原型/admin/menus.html 与图鉴 dialogs.html 第 9 组。
 * 弹窗：dlg-node-form（D9-01 新增/编辑节点）、dlg-node-del（D9-02 删除节点）、
 *       dlg-node-parent（D9-03 上级节点不能选自己或子孙）。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHead from '@/components/PageHead.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import NavIcon from '@/components/NavIcon.vue'
import FormDialog from '@/components/FormDialog.vue'
import NoticeDialog from '@/components/NoticeDialog.vue'
import { deletePermission, fetchPermissionTree, savePermission } from '@/api/menus'
import { fetchRoles } from '@/api/roles'
import { MENU_ICON_OPTIONS } from '@/config/nav'
import { useAuthStore } from '@/stores/auth'
import {
  collectExpandableIds,
  flattenVisibleTree,
  mapPermissionTree,
  type FlatPermNode,
} from '@/utils/menu'
import { unwrapList, unwrapPage } from '@/utils/page'

const auth = useAuthStore()

type FormState = {
  parentId: string
  code: string
  name: string
  type: string
  path: string
  icon: string
  sort: number | string
  visible: boolean
  /** 图鉴 D9-01 未展示、提交时原样带回的字段 */
  component: string
  status: string
}

const tree = ref<FlatPermNode[]>([])
const expanded = ref<Set<string>>(new Set())
const keyword = ref('')
const typeFilter = ref('')

/* ===== 新增 / 编辑节点（D9-01）===== */
const formDialog = ref(false)
const editingId = ref<string | null>(null)
const formSaving = ref(false)
const formError = ref('')
const form = ref<FormState>(emptyForm())
const formErrors = ref<{ code?: string; name?: string; path?: string; sort?: string }>({})

/* ===== 删除节点（D9-02）===== */
const delDialog = ref(false)
const delTarget = ref<FlatPermNode | null>(null)
const delSaving = ref(false)
const delError = ref('')
const delChildren = ref<string[]>([])
const delRoles = ref<string[]>([])

/* ===== 上级节点成环：拒绝（D9-03）===== */
const rejectDialog = ref(false)
const rejectNode = ref<FlatPermNode | null>(null)
const rejectChildren = ref<string[]>([])

const visibleRows = computed(() => {
  let rows = flattenVisibleTree(tree.value, expanded.value)
  if (typeFilter.value) {
    rows = rows.filter((r) => r.type === typeFilter.value)
  }
  if (keyword.value.trim()) {
    const q = keyword.value.trim().toLowerCase()
    rows = rows.filter(
      (r) =>
        r.name.toLowerCase().includes(q) ||
        r.code.toLowerCase().includes(q) ||
        r.path.toLowerCase().includes(q),
    )
  }
  return rows
})

const pageSub = computed(
  () => `目录 / 菜单 / 按钮 / 接口 · 权限码 · 排序与显隐（共 ${countNodes(tree.value)} 个节点）`,
)

/** 「顶级节点 + 整棵菜单树」；禁止挂在按钮下（按钮没有下级） */
const parentOptions = computed(() => {
  const out: Array<{ id: string; label: string }> = [{ id: '0', label: '顶级节点' }]
  const walk = (nodes: FlatPermNode[], depth: number) => {
    for (const n of nodes) {
      if (n.type === 'BUTTON') continue
      out.push({
        id: n.id,
        label: depth === 0 ? n.name : `${'　'.repeat(depth)}└ ${n.name}`,
      })
      if (n.children.length) walk(n.children, depth + 1)
    }
  }
  walk(tree.value, 0)
  return out
})

const delLabel = computed(() => nodeLabel(delTarget.value))
const delChildPreview = computed(() => preview(delChildren.value, 4))
const delRolePreview = computed(() => preview(delRoles.value, 3))
const rejectChildPreview = computed(() => preview(rejectChildren.value, 3))

function nodeLabel(node: FlatPermNode | null): string {
  if (!node) return ''
  return node.code ? `${node.name}（${node.code}）` : node.name
}

/** 「商品列表、类目与品牌、新增商品、编辑商品 …」 */
function preview(names: string[], max: number): string {
  if (!names.length) return ''
  const head = names.slice(0, max).join('、')
  return names.length > max ? `${head} …` : head
}

function countNodes(nodes: FlatPermNode[]): number {
  let n = 0
  for (const node of nodes) {
    n += 1 + countNodes(node.children)
  }
  return n
}

function descendantNames(node: FlatPermNode): string[] {
  const out: string[] = []
  const walk = (list: FlatPermNode[]) => {
    for (const n of list) {
      out.push(n.name)
      walk(n.children)
    }
  }
  walk(node.children)
  return out
}

function emptyForm(): FormState {
  return {
    parentId: '0',
    code: '',
    name: '',
    type: 'MENU',
    path: '',
    icon: 'Menu',
    sort: 0,
    visible: true,
    component: '',
    status: 'ENABLED',
  }
}

function typeMeta(type: string) {
  const t = type.toUpperCase()
  if (t === 'DIR') return { label: '目录', tone: 'amber' as const }
  if (t === 'MENU') return { label: '菜单', tone: 'coral' as const }
  if (t === 'BUTTON') return { label: '按钮', tone: 'soft-forest' as const }
  if (t === 'API') return { label: '接口', tone: 'brick' as const }
  return { label: type || '—', tone: 'coral' as const }
}

/** 按钮 / 接口仅作为权限点：原型里「显隐」列显示 — */
function isPermOnly(type: string): boolean {
  const t = type.toUpperCase()
  return t === 'BUTTON' || t === 'API'
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

async function load() {
  try {
    const res = await fetchPermissionTree()
    tree.value = mapPermissionTree(unwrapList<Record<string, unknown>>(res.data))
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

function openCreate(parent?: FlatPermNode) {
  editingId.value = null
  formError.value = ''
  formErrors.value = {}
  form.value = emptyForm()
  if (parent) {
    form.value.parentId = parent.id
    form.value.type = parent.type === 'MENU' ? 'BUTTON' : 'MENU'
    form.value.sort = (parent.children?.length ?? 0) + 1
  }
  formDialog.value = true
}

function openEdit(row: FlatPermNode) {
  editingId.value = row.id
  formError.value = ''
  formErrors.value = {}
  form.value = {
    parentId: row.parentId || '0',
    code: row.code,
    name: row.name,
    type: row.type,
    path: row.path,
    icon: row.icon || 'Menu',
    sort: row.sort,
    visible: row.visible,
    component: row.component,
    status: row.status || 'ENABLED',
  }
  formDialog.value = true
}

/** 自己或自己的子孙不能当上级（图鉴 D9-03） */
function isCycleParent(): boolean {
  const id = editingId.value
  if (!id || form.value.parentId === '0') return false
  if (form.value.parentId === id) return true
  const row = findRow(tree.value, id)
  if (!row) return false
  return collectIds(row).includes(form.value.parentId)
}

function findRow(nodes: FlatPermNode[], id: string): FlatPermNode | null {
  for (const n of nodes) {
    if (n.id === id) return n
    const hit = findRow(n.children, id)
    if (hit) return hit
  }
  return null
}

function collectIds(node: FlatPermNode): string[] {
  const ids: string[] = []
  const walk = (list: FlatPermNode[]) => {
    for (const n of list) {
      ids.push(n.id)
      walk(n.children)
    }
  }
  walk([node])
  return ids
}

function openParentReject() {
  const row = editingId.value ? findRow(tree.value, editingId.value) : null
  rejectNode.value = row
  rejectChildren.value = row ? descendantNames(row) : []
  formDialog.value = false
  rejectDialog.value = true
}

async function submit() {
  const errors: typeof formErrors.value = {}
  const code = form.value.code.trim()
  const name = form.value.name.trim()
  const path = form.value.path.trim()
  if (!name) errors.name = '请填写名称'
  else if (name.length > 30) errors.name = '名称为 1～30 字符'
  if (!code) errors.code = '请填写权限码'
  else if (!/^[A-Za-z0-9:_]{2,50}$/.test(code)) {
    errors.code = '权限码为 2～50 位，仅字母 / 数字 / 冒号 / 下划线'
  }
  if (form.value.type === 'MENU' && !path) errors.path = '菜单类型请填写路由路径'
  const sort = Number(form.value.sort)
  if (form.value.sort === '' || Number.isNaN(sort)) errors.sort = '请填写排序'
  else if (!Number.isInteger(sort) || sort < 0 || sort > 999) errors.sort = '排序为 0～999 的整数'

  formErrors.value = errors
  formError.value = ''
  if (Object.keys(errors).length) return

  if (isCycleParent()) {
    openParentReject()
    return
  }

  formSaving.value = true
  try {
    await savePermission(
      {
        parentId: form.value.parentId === '0' ? 0 : form.value.parentId,
        code,
        name,
        type: form.value.type,
        path: path || undefined,
        component: form.value.component || undefined,
        icon: form.value.icon || undefined,
        sort,
        visible: !!form.value.visible,
        status: form.value.status,
      },
      editingId.value || undefined,
    )
    ElMessage.success(editingId.value ? '已更新' : '已创建')
    formDialog.value = false
    await load()
    await auth.loadProfile()
  } catch (e: unknown) {
    formError.value = readErrMsg(e) || '保存失败，请稍后重试'
  } finally {
    formSaving.value = false
  }
}

/* ===== 删除节点（D9-02）===== */
/** 删除确认里的影响面：下级节点 + 仍勾选该节点的角色 */
async function loadRoleRefs(nodeId: string): Promise<string[]> {
  try {
    const res = await fetchRoles({ page: 1, pageSize: 100 })
    const pageData = unwrapPage<Record<string, unknown>>(res.data)
    const list = pageData?.records ?? unwrapList<Record<string, unknown>>(res.data)
    return list
      .filter((r) => {
        const ids = Array.isArray(r.permissionIds)
          ? (r.permissionIds as unknown[]).map(String)
          : []
        return ids.includes(nodeId)
      })
      .map((r) => String(r.name ?? r.code ?? ''))
      .filter(Boolean)
  } catch {
    return []
  }
}

function openDelete(row: FlatPermNode) {
  delTarget.value = row
  delError.value = ''
  delChildren.value = descendantNames(row)
  delRoles.value = []
  delDialog.value = true
  loadRoleRefs(row.id).then((names) => {
    if (delTarget.value?.id === row.id) delRoles.value = names
  })
}

async function confirmDelete() {
  const row = delTarget.value
  if (!row) return
  delSaving.value = true
  delError.value = ''
  try {
    await deletePermission(row.id)
    ElMessage.success('已删除')
    delDialog.value = false
    await load()
    await auth.loadProfile()
  } catch (e: unknown) {
    // 仍有子节点 / 角色引用时后端拒绝：把 msg 内联显示在弹窗里，不静默
    delError.value = readErrMsg(e) || '删除失败，请稍后重试'
  } finally {
    delSaving.value = false
  }
}

onMounted(load)
</script>

<template>
  <PageHead title="菜单与权限" :sub="pageSub">
    <template #actions>
      <button class="btn btn-secondary btn-sm" type="button" @click="expandAll">全部展开</button>
      <button class="btn btn-secondary btn-sm" type="button" @click="collapseAll">全部收起</button>
      <button class="btn btn-primary" type="button" @click="openCreate()">+ 新增节点</button>
    </template>
  </PageHead>

  <div class="card card-pad">
    <div class="toolbar">
      <input v-model="keyword" class="input" type="text" placeholder="名称 / 权限码 / 路由" />
      <select v-model="typeFilter" class="select">
        <option value="">全部类型</option>
        <option value="DIR">目录</option>
        <option value="MENU">菜单</option>
        <option value="BUTTON">按钮</option>
        <option value="API">接口</option>
      </select>
      <div class="toolbar-actions">
        <button
          class="btn btn-secondary btn-sm"
          type="button"
          @click="keyword = ''; typeFilter = ''"
        >
          重置
        </button>
      </div>
    </div>

    <table class="table">
      <thead>
        <tr>
          <th class="name-col">名称</th>
          <th>类型</th>
          <th class="code">路由</th>
          <th class="code">权限码</th>
          <th>图标</th>
          <th class="num center">排序</th>
          <th>显隐</th>
          <th>状态</th>
          <th class="col-op">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="!visibleRows.length">
          <td colspan="9" class="hint empty-cell">暂无菜单</td>
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
              <span class="tree-label" :class="{ 'tree-label-root': row.depth === 0 }">
                {{ row.name }}
              </span>
            </div>
          </td>
          <td>
            <StatusBadge :text="typeMeta(row.type).label" :tone="typeMeta(row.type).tone" />
          </td>
          <td class="code">{{ row.path || '—' }}</td>
          <td class="code">{{ row.code || '—' }}</td>
          <td>
            <span v-if="row.icon" class="icon-cell">
              <NavIcon :name="row.icon" />
              <span class="hint">{{ row.icon }}</span>
            </span>
            <span v-else class="hint">—</span>
          </td>
          <td class="num center">{{ row.sort }}</td>
          <td :class="{ hint: isPermOnly(row.type) }">
            {{ isPermOnly(row.type) ? '—' : row.visible ? '显示' : '隐藏' }}
          </td>
          <td>
            <StatusBadge
              :text="row.status === 'ENABLED' ? '启用' : '禁用'"
              :tone="row.status === 'ENABLED' ? 'forest' : 'brick'"
            />
          </td>
          <td class="col-op">
            <button
              v-if="row.type !== 'BUTTON'"
              class="btn btn-ghost btn-sm"
              type="button"
              @click="openCreate(row)"
            >
              下级
            </button>
            <button class="btn btn-ghost btn-sm" type="button" @click="openEdit(row)">编辑</button>
            <button class="btn btn-ghost btn-sm" type="button" @click="openDelete(row)">删除</button>
          </td>
        </tr>
      </tbody>
    </table>
  </div>

  <!-- 新增 / 编辑节点（D9-01 · dlg-node-form） -->
  <FormDialog
    v-model="formDialog"
    :title="editingId ? '编辑节点' : '新增节点'"
    icon="info"
    icon-text="✎"
    width="is-lg"
    cancel-text="取消"
    confirm-text="保存"
    :loading="formSaving"
    @submit="submit"
  >
    <p v-if="formError" class="error-text">{{ formError }}</p>
    <div class="form-grid">
      <div class="field">
        <label class="label">节点类型<span class="req">*</span></label>
        <select v-model="form.type" class="select">
          <option value="DIR">目录</option>
          <option value="MENU">菜单</option>
          <option value="BUTTON">按钮</option>
          <option value="API">接口</option>
        </select>
        <span class="hint">目录 / 菜单 显示在侧栏；按钮 / 接口 仅作为权限点</span>
      </div>
      <div class="field">
        <label class="label">名称<span class="req">*</span></label>
        <input v-model="form.name" class="input" maxlength="30" />
        <span class="hint">1～30 字符</span>
        <span v-if="formErrors.name" class="error-text">{{ formErrors.name }}</span>
      </div>
      <div class="field">
        <label class="label">权限码<span class="req">*</span></label>
        <input v-model="form.code" class="input" maxlength="50" />
        <span class="hint">
          2～50 位，仅字母 / 数字 / 冒号 / 下划线，如 <span class="code">product:edit</span>
        </span>
        <span v-if="formErrors.code" class="error-text">{{ formErrors.code }}</span>
      </div>
      <div class="field">
        <label class="label">上级节点<span class="req">*</span></label>
        <select v-model="form.parentId" class="select">
          <option v-for="p in parentOptions" :key="p.id" :value="p.id">{{ p.label }}</option>
        </select>
        <span class="hint">不能选自己或自己的子孙节点（防止菜单树成环）</span>
      </div>
      <div class="field">
        <label class="label">路由 path</label>
        <input v-model="form.path" class="input" placeholder="/products" />
        <span class="hint">菜单类型必填，目录 / 按钮 / 接口留空</span>
        <span v-if="formErrors.path" class="error-text">{{ formErrors.path }}</span>
      </div>
      <div class="field">
        <label class="label">图标</label>
        <div class="icon-picker">
          <select v-model="form.icon" class="select">
            <option v-for="ico in MENU_ICON_OPTIONS" :key="ico" :value="ico">{{ ico }}</option>
          </select>
          <NavIcon :name="form.icon" />
        </div>
        <span class="hint">侧栏图标标识，目录 / 菜单使用</span>
      </div>
      <div class="field">
        <label class="label">排序<span class="req">*</span></label>
        <input v-model.number="form.sort" class="input" type="number" min="0" max="999" />
        <span class="hint">0～999，同级越小越靠前</span>
        <span v-if="formErrors.sort" class="error-text">{{ formErrors.sort }}</span>
      </div>
      <div class="field">
        <label class="label">侧栏可见</label>
        <label class="switch switch-field">
          <input v-model="form.visible" type="checkbox" />
          <span class="track" />
        </label>
        <span class="hint">关闭后节点仍生效，但不在侧栏展示</span>
      </div>
    </div>
    <div class="alert alert-info">
      权限码是角色授权的唯一依据，改动会影响已授权角色；<b>按钮 / 接口</b>类型的权限码建议用
      <span class="code">模块:动作</span> 形式。
    </div>
  </FormDialog>

  <!-- 删除节点（D9-02 · dlg-node-del） -->
  <FormDialog
    v-model="delDialog"
    title="删除节点"
    icon="danger"
    width="is-md"
    cancel-text="取消"
    confirm-text="确认删除"
    :loading="delSaving"
    @submit="confirmDelete"
  >
    <p v-if="delError" class="error-text">{{ delError }}</p>
    <p>确认删除节点「{{ delLabel }}」？</p>
    <ul class="impact">
      <li v-if="delChildren.length">
        先移除 / 迁移其<b>下级节点</b>：{{ delChildPreview }}
      </li>
      <li v-else>该节点当前<b>没有下级节点</b></li>
      <li v-if="delRoles.length">先解除<b>角色引用</b>：{{ delRolePreview }} 等角色当前勾选了该节点</li>
      <li v-else>暂无<b>角色</b>勾选该节点</li>
    </ul>
    <div class="alert alert-warn impact-alert">
      节点仍存在子节点或角色引用时，删除会被拒绝；删除后该权限码立即失效，菜单树不可恢复。
    </div>
  </FormDialog>

  <!-- 上级节点不能选自己或子孙（D9-03 · dlg-node-parent） -->
  <NoticeDialog
    v-model="rejectDialog"
    title="上级节点不能选自己或子孙"
    icon="warn"
    width="is-sm"
    confirm-text="知道了"
  >
    <p>
      「{{ rejectNode?.name }}」的上级不能是它自己，也不能是其子孙节点{{
        rejectChildPreview ? `（${rejectChildPreview}）` : ''
      }}。
    </p>
    <p class="hint">
      该限制用于防止菜单树成环。请改选其他上级节点，或先把下级节点迁移到别处。
    </p>
  </NoticeDialog>
</template>

<style scoped>
.name-col {
  min-width: 200px;
}
.empty-cell {
  text-align: center;
  padding: 24px;
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
.tree-label-root {
  font-weight: 700;
}
.icon-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.icon-picker {
  display: flex;
  align-items: center;
  gap: 10px;
}
.switch-field {
  margin-top: 4px;
}
.impact-alert {
  margin-top: 12px;
}
</style>