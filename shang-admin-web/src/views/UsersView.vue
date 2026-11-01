<script setup lang="ts">
/**
 * 用户管理 —— 对齐原型 .stitch/原型/admin/users.html 与图鉴 dialogs.html 第 7 组。
 * 弹窗：dlg-user-form（D7-01 新增/编辑）、dlg-user-reset（D7-02 重置密码）、
 *       dlg-user-disable（D7-03 禁用）、dlg-user-enable（D7-04 启用）、
 *       dlg-user-del（D7-05 删除）、dlg-user-admin（D7-06 超管不可删除）、
 *       dlg-user-self（D7-07 不能禁用自己）。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHead from '@/components/PageHead.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import ListPager from '@/components/ListPager.vue'
import FormDialog from '@/components/FormDialog.vue'
import NoticeDialog from '@/components/NoticeDialog.vue'
import {
  deleteUser,
  fetchRolesAll,
  fetchUsers,
  resetUserPassword,
  saveUser,
  updateUserStatus,
} from '@/api/users'
import { fetchDeptTree } from '@/api/depts'
import { usePager } from '@/composables/usePager'
import { useAuthStore } from '@/stores/auth'
import { unwrapList, unwrapPage } from '@/utils/page'
import { formatDateTime } from '@/utils/datetime'

type UserRow = {
  id: number
  username: string
  name: string
  phone: string
  rawPhone: string
  deptId: string
  dept: string
  email: string
  role: string
  roleIds: number[]
  roleCodes: string[]
  roleTone: 'amber' | 'coral'
  status: string
  rawStatus: string
  statusTone: 'forest' | 'brick'
  lastLogin: string
}

type RoleOpt = { id: number; name: string; code: string }
type DeptOpt = { id: string; name: string }

/** 拒绝类弹窗（图鉴 D7-06 / D7-07 与超管禁用的同款保护说明） */
type RejectKind = 'superDelete' | 'superDisable' | 'selfDisable'

const auth = useAuthStore()

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

const keyword = ref('')
const statusFilter = ref('')
const deptFilter = ref('')
const rows = ref<UserRow[]>([])
const roles = ref<RoleOpt[]>([])
const depts = ref<DeptOpt[]>([])

/* ===== 新增 / 编辑用户（D7-01）===== */
const formDialog = ref(false)
const editingId = ref<number | null>(null)
const formSaving = ref(false)
const formError = ref('')
const form = ref({
  username: '',
  password: '',
  realName: '',
  phone: '',
  email: '',
  deptId: '',
  status: 'ENABLED',
  roleIds: [] as number[],
})
const formErrors = ref<{
  username?: string
  realName?: string
  password?: string
  phone?: string
  email?: string
  roleIds?: string
}>({})

/* ===== 重置密码（D7-02）===== */
const pwdDialog = ref(false)
const pwdTarget = ref<UserRow | null>(null)
const pwdSaving = ref(false)
const pwdError = ref('')
const pwdForm = ref({ password: '', confirm: '' })
const pwdErrors = ref<{ password?: string; confirm?: string }>({})

/* ===== 禁用 / 启用（D7-03 / D7-04）===== */
const statusDialog = ref(false)
const statusTarget = ref<UserRow | null>(null)
const statusNext = ref<'ENABLED' | 'DISABLED'>('DISABLED')
const statusSaving = ref(false)
const statusError = ref('')

/* ===== 删除（D7-05）===== */
const delDialog = ref(false)
const delTarget = ref<UserRow | null>(null)
const delSaving = ref(false)
const delError = ref('')

/* ===== 拒绝（D7-06 / D7-07）===== */
const rejectDialog = ref(false)
const rejectKind = ref<RejectKind>('superDelete')
const rejectTarget = ref<UserRow | null>(null)
const rejectMsg = ref('')

const roleById = computed(() => new Map(roles.value.map((r) => [r.id, r])))
const deptNameById = computed(() => new Map(depts.value.map((d) => [d.id, d.name])))

const rejectTitle = computed(() => {
  if (rejectKind.value === 'selfDisable') return '无法禁用当前账号'
  return rejectKind.value === 'superDelete' ? '无法删除用户' : '无法禁用用户'
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

function isSuperAdmin(row: UserRow): boolean {
  return row.id === 1 || row.roleCodes.includes('SUPER_ADMIN')
}

function isSelf(row: UserRow): boolean {
  const me = auth.user
  if (!me) return false
  if (me.id != null && String(me.id) === String(row.id)) return true
  return Boolean(me.username) && me.username === row.username
}

async function loadRoles() {
  try {
    const res = await fetchRolesAll()
    roles.value = unwrapList<Record<string, unknown>>(res.data).map((r) => ({
      id: Number(r.id),
      name: String(r.name ?? r.code ?? ''),
      code: String(r.code ?? ''),
    }))
  } catch {
    roles.value = []
  }
}

async function loadDepts() {
  try {
    const res = await fetchDeptTree()
    const out: DeptOpt[] = []
    const walk = (nodes: Record<string, unknown>[]) => {
      for (const n of nodes) {
        out.push({ id: String(n.id ?? ''), name: String(n.name ?? '') })
        if (Array.isArray(n.children)) walk(n.children as Record<string, unknown>[])
      }
    }
    walk(unwrapList<Record<string, unknown>>(res.data))
    depts.value = out
  } catch {
    depts.value = []
  }
}

async function load() {
  try {
    const res = await fetchUsers({
      keyword: keyword.value || undefined,
      status: statusFilter.value || undefined,
      deptId: deptFilter.value || undefined,
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
    rows.value = pageData.records.map((u) => {
      const roleIds = Array.isArray(u.roleIds) ? (u.roleIds as number[]).map(Number) : []
      const codes = roleIds.map((id) => roleById.value.get(id)?.code ?? '').filter(Boolean)
      const roleNames = roleIds
        .map((id) => roleById.value.get(id)?.name)
        .filter(Boolean)
        .join('、')
      const rawStatus = String(u.status ?? '')
      const deptId = u.deptId == null ? '' : String(u.deptId)
      const phone = String(u.phone ?? '')
      return {
        id: Number(u.id),
        username: String(u.username ?? ''),
        name: String(u.realName ?? u.username ?? ''),
        phone: phone || '—',
        rawPhone: phone,
        deptId,
        dept: deptNameById.value.get(deptId) ?? '—',
        email: String(u.email ?? ''),
        role: roleNames || '—',
        roleIds,
        roleCodes: codes,
        roleTone: codes.includes('SUPER_ADMIN') ? ('amber' as const) : ('coral' as const),
        status: rawStatus === 'ENABLED' ? '启用' : '禁用',
        rawStatus,
        statusTone: (rawStatus === 'ENABLED' ? 'forest' : 'brick') as 'forest' | 'brick',
        lastLogin: formatDateTime(u.lastLoginAt),
      }
    })
  } catch {
    rows.value = []
    total.value = 0
  }
}

function search() {
  resetPage()
  return load()
}

function resetFilters() {
  keyword.value = ''
  statusFilter.value = ''
  deptFilter.value = ''
  search()
}

/* ===== 新增 / 编辑用户 ===== */
function openCreate() {
  editingId.value = null
  formError.value = ''
  formErrors.value = {}
  form.value = {
    username: '',
    password: '',
    realName: '',
    phone: '',
    email: '',
    deptId: '',
    status: 'ENABLED',
    roleIds: [],
  }
  formDialog.value = true
}

function openEdit(row: UserRow) {
  editingId.value = row.id
  formError.value = ''
  formErrors.value = {}
  form.value = {
    username: row.username,
    password: '',
    realName: row.name,
    phone: row.rawPhone,
    email: row.email,
    deptId: row.deptId,
    status: row.rawStatus || 'ENABLED',
    roleIds: [...row.roleIds],
  }
  formDialog.value = true
}

function toggleRole(id: number) {
  const set = new Set(form.value.roleIds)
  if (set.has(id)) set.delete(id)
  else set.add(id)
  form.value.roleIds = [...set]
}

async function submitUser() {
  const errors: typeof formErrors.value = {}
  const username = form.value.username.trim()
  const realName = form.value.realName.trim()
  const phone = form.value.phone.trim()
  const email = form.value.email.trim()

  if (!username) errors.username = '请填写用户名'
  else if (!/^[A-Za-z][A-Za-z0-9_]{3,29}$/.test(username)) {
    errors.username = '用户名为 4～30 位，字母开头，仅字母 / 数字 / 下划线'
  }
  if (!realName) errors.realName = '请填写姓名'
  else if (realName.length > 30) errors.realName = '姓名为 1～30 字符'

  if (!editingId.value && !form.value.password) errors.password = '请填写初始密码'
  else if (form.value.password && !/^(?=.*[A-Za-z])(?=.*\d)[\S]{8,20}$/.test(form.value.password)) {
    errors.password = '密码为 8～20 位，须同时包含字母与数字'
  }

  if (!phone) errors.phone = '请填写手机号'
  else if (!/^1[3-9]\d{9}$/.test(phone)) errors.phone = '手机号为 11 位数字'

  if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) errors.email = '邮箱格式不正确'
  if (!form.value.roleIds.length) errors.roleIds = '请至少选择一个角色'

  formErrors.value = errors
  formError.value = ''
  if (Object.keys(errors).length) return

  formSaving.value = true
  try {
    await saveUser(
      {
        username: editingId.value ? undefined : username,
        password: form.value.password || undefined,
        realName,
        phone,
        email: email || undefined,
        deptId: form.value.deptId ? Number(form.value.deptId) : null,
        status: form.value.status,
        roleIds: form.value.roleIds,
      },
      editingId.value ?? undefined,
    )
    ElMessage.success(editingId.value ? '已更新' : '已创建')
    formDialog.value = false
    await load()
  } catch (e: unknown) {
    formError.value = readErrMsg(e) || '保存失败，请稍后重试'
  } finally {
    formSaving.value = false
  }
}

/* ===== 重置密码（D7-02）===== */
function openReset(row: UserRow) {
  pwdTarget.value = row
  pwdError.value = ''
  pwdErrors.value = {}
  pwdForm.value = { password: '', confirm: '' }
  pwdDialog.value = true
}

async function submitReset() {
  const errors: typeof pwdErrors.value = {}
  if (!pwdForm.value.password) errors.password = '请填写新密码'
  else if (!/^(?=.*[A-Za-z])(?=.*\d)[\S]{8,20}$/.test(pwdForm.value.password)) {
    errors.password = '新密码为 8～20 位，须同时包含字母与数字'
  }
  if (!pwdForm.value.confirm) errors.confirm = '请再次输入新密码'
  else if (pwdForm.value.confirm !== pwdForm.value.password) {
    errors.confirm = '两次输入的新密码不一致'
  }
  pwdErrors.value = errors
  pwdError.value = ''
  if (Object.keys(errors).length) return

  const row = pwdTarget.value
  if (!row) return
  pwdSaving.value = true
  try {
    await resetUserPassword(row.id, pwdForm.value.password)
    ElMessage.success('密码已重置')
    pwdDialog.value = false
  } catch (e: unknown) {
    pwdError.value = readErrMsg(e) || '重置失败，请稍后重试'
  } finally {
    pwdSaving.value = false
  }
}

/* ===== 拒绝弹窗 ===== */
function openReject(kind: RejectKind, row: UserRow, msg = '') {
  rejectKind.value = kind
  rejectTarget.value = row
  rejectMsg.value = msg
  rejectDialog.value = true
}

/* ===== 禁用 / 启用（D7-03 / D7-04）===== */
function requestToggleStatus(row: UserRow) {
  const next = row.rawStatus === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  if (next === 'DISABLED') {
    if (isSelf(row)) {
      openReject('selfDisable', row)
      return
    }
    if (isSuperAdmin(row)) {
      openReject('superDisable', row)
      return
    }
  }
  statusTarget.value = row
  statusNext.value = next
  statusError.value = ''
  statusDialog.value = true
}

async function confirmToggleStatus() {
  const row = statusTarget.value
  if (!row) return
  statusSaving.value = true
  statusError.value = ''
  try {
    await updateUserStatus(row.id, statusNext.value)
    ElMessage.success(statusNext.value === 'ENABLED' ? '已启用' : '已禁用')
    statusDialog.value = false
    await load()
  } catch (e: unknown) {
    const msg = readErrMsg(e)
    // 后端也会拦超管：翻成图鉴里的保护说明，而不是一条通用 toast
    if (msg.includes('超管')) {
      statusDialog.value = false
      openReject('superDisable', row, msg)
      return
    }
    statusError.value = msg || '操作失败，请稍后重试'
  } finally {
    statusSaving.value = false
  }
}

/* ===== 删除（D7-05 / D7-06）===== */
function requestDelete(row: UserRow) {
  if (isSuperAdmin(row)) {
    openReject('superDelete', row)
    return
  }
  delTarget.value = row
  delError.value = ''
  delDialog.value = true
}

async function confirmDelete() {
  const row = delTarget.value
  if (!row) return
  delSaving.value = true
  delError.value = ''
  try {
    await deleteUser(row.id)
    ElMessage.success('已删除')
    delDialog.value = false
    await load()
  } catch (e: unknown) {
    const msg = readErrMsg(e)
    if (msg.includes('超管')) {
      delDialog.value = false
      openReject('superDelete', row, msg)
      return
    }
    delError.value = msg || '删除失败，请稍后重试'
  } finally {
    delSaving.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadRoles(), loadDepts()])
  await load()
})
</script>

<template>
  <div class="list-page">
    <PageHead title="用户管理" sub="后台用户 · 角色绑定 · 启用/禁用 · 超管账号受保护">
      <template #actions>
        <button class="btn btn-primary" type="button" @click="openCreate">+ 新增用户</button>
      </template>
    </PageHead>

    <div class="card card-pad list-card">
      <div class="toolbar">
        <input
          v-model="keyword"
          class="input"
          placeholder="搜索用户名 / 手机号"
          @keyup.enter="search"
        />
        <select v-model="statusFilter" class="select">
          <option value="">全部状态</option>
          <option value="ENABLED">启用</option>
          <option value="DISABLED">禁用</option>
        </select>
        <select v-model="deptFilter" class="select" @change="search">
          <option value="">全部部门</option>
          <option v-for="d in depts" :key="d.id" :value="d.id">{{ d.name }}</option>
        </select>
        <div class="toolbar-actions">
          <button class="btn btn-secondary btn-sm" type="button" @click="resetFilters">重置</button>
          <button class="btn btn-primary btn-sm" type="button" @click="search">查询</button>
        </div>
      </div>

      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th class="code">用户名</th>
              <th>姓名</th>
              <th class="code">手机号</th>
              <th>部门</th>
              <th>角色</th>
              <th>状态</th>
              <th class="num">最近登录</th>
              <th class="col-op">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!rows.length">
              <td colspan="8" class="hint empty-cell">暂无用户</td>
            </tr>
            <tr v-for="row in rows" :key="row.id">
              <td class="code">{{ row.username }}</td>
              <td>{{ row.name }}</td>
              <td class="code">{{ row.phone }}</td>
              <td>{{ row.dept }}</td>
              <td><StatusBadge :text="row.role" :tone="row.roleTone" /></td>
              <td><StatusBadge :text="row.status" :tone="row.statusTone" /></td>
              <td class="num">{{ row.lastLogin }}</td>
              <td class="col-op">
                <button class="btn btn-ghost btn-sm" type="button" @click="openEdit(row)">编辑</button>
                <button class="btn btn-ghost btn-sm" type="button" @click="openReset(row)">重置</button>
                <button class="btn btn-ghost btn-sm" type="button" @click="requestToggleStatus(row)">
                  {{ row.rawStatus === 'ENABLED' ? '禁用' : '启用' }}
                </button>
                <button class="btn btn-ghost btn-sm" type="button" @click="requestDelete(row)">
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
  </div>

  <!-- 新增 / 编辑用户（D7-01 · dlg-user-form） -->
  <FormDialog
    v-model="formDialog"
    :title="editingId ? '编辑用户' : '新增用户'"
    icon="info"
    icon-text="✎"
    width="is-lg"
    cancel-text="取消"
    confirm-text="保存"
    :loading="formSaving"
    @submit="submitUser"
  >
    <p v-if="formError" class="error-text">{{ formError }}</p>
    <div class="form-grid">
      <div class="field">
        <label class="label">用户名<span class="req">*</span></label>
        <input
          v-model="form.username"
          class="input"
          :class="{ 'input-readonly': !!editingId }"
          :readonly="!!editingId"
          maxlength="30"
        />
        <span class="hint">4～30 位，字母开头，仅字母 / 数字 / 下划线</span>
        <span v-if="formErrors.username" class="error-text">{{ formErrors.username }}</span>
      </div>
      <div class="field">
        <label class="label">姓名<span class="req">*</span></label>
        <input v-model="form.realName" class="input" maxlength="30" />
        <span class="hint">1～30 字符</span>
        <span v-if="formErrors.realName" class="error-text">{{ formErrors.realName }}</span>
      </div>
      <div class="field">
        <label class="label">密码</label>
        <input v-model="form.password" class="input" type="password" placeholder="留空则不修改" />
        <span class="hint">8～20 位，须同时包含字母与数字；新增时必填</span>
        <span v-if="formErrors.password" class="error-text">{{ formErrors.password }}</span>
      </div>
      <div class="field">
        <label class="label">手机号<span class="req">*</span></label>
        <input v-model="form.phone" class="input" maxlength="11" />
        <span class="hint">11 位手机号</span>
        <span v-if="formErrors.phone" class="error-text">{{ formErrors.phone }}</span>
      </div>
      <div class="field">
        <label class="label">邮箱</label>
        <input v-model="form.email" class="input" />
        <span class="hint">标准邮箱格式</span>
        <span v-if="formErrors.email" class="error-text">{{ formErrors.email }}</span>
      </div>
      <div class="field">
        <label class="label">部门</label>
        <select v-model="form.deptId" class="select">
          <option value="">请选择部门</option>
          <option v-for="d in depts" :key="d.id" :value="d.id">{{ d.name }}</option>
        </select>
      </div>
      <div class="field full">
        <label class="label">角色<span class="req">*</span>（可多选，至少 1 个）</label>
        <div class="role-box">
          <label v-for="r in roles" :key="r.id">
            <input
              type="checkbox"
              :checked="form.roleIds.includes(r.id)"
              @change="toggleRole(r.id)"
            />
            {{ r.name }}
          </label>
        </div>
        <span v-if="formErrors.roleIds" class="error-text">{{ formErrors.roleIds }}</span>
      </div>
      <div class="field full">
        <label class="label">状态</label>
        <select v-model="form.status" class="select status-select">
          <option value="ENABLED">启用</option>
          <option value="DISABLED">禁用</option>
        </select>
      </div>
    </div>
    <div class="alert alert-info">
      用户名在系统内唯一，重复时提交会返回「用户名已存在」；新增用户默认状态为<b>启用</b>。
    </div>
  </FormDialog>

  <!-- 重置密码（D7-02 · dlg-user-reset） -->
  <FormDialog
    v-model="pwdDialog"
    :title="`重置密码 · ${pwdTarget?.username ?? ''}`"
    icon="warn"
    icon-text="🔑"
    width="is-md"
    cancel-text="取消"
    confirm-text="确认重置"
    :loading="pwdSaving"
    @submit="submitReset"
  >
    <p class="hint reset-meta">
      用户 <b>{{ pwdTarget?.name }}（{{ pwdTarget?.username }}）</b> · {{ pwdTarget?.dept }} ·
      {{ pwdTarget?.role }}
    </p>
    <p v-if="pwdError" class="error-text">{{ pwdError }}</p>
    <div class="field">
      <label class="label">新密码<span class="req">*</span></label>
      <input
        v-model="pwdForm.password"
        class="input"
        type="password"
        placeholder="请输入新密码"
        autofocus
      />
      <span class="hint">8～20 位，须同时包含字母与数字</span>
      <span v-if="pwdErrors.password" class="error-text">{{ pwdErrors.password }}</span>
    </div>
    <div class="field">
      <label class="label">确认新密码<span class="req">*</span></label>
      <input v-model="pwdForm.confirm" class="input" type="password" placeholder="再次输入新密码" />
      <span v-if="pwdErrors.confirm" class="error-text">{{ pwdErrors.confirm }}</span>
    </div>
    <div class="alert alert-warn">
      提交后该用户的<b>所有在线会话立即失效</b>，需使用新密码重新登录。管理员无法查看原密码。
    </div>
  </FormDialog>

  <!-- 禁用用户（D7-03 · dlg-user-disable） -->
  <FormDialog
    v-model="statusDialog"
    :title="statusNext === 'ENABLED' ? '启用用户' : '禁用用户'"
    :icon="statusNext === 'ENABLED' ? 'ok' : 'danger'"
    :icon-text="statusNext === 'ENABLED' ? '✓' : '!'"
    width="is-sm"
    cancel-text="取消"
    :confirm-text="statusNext === 'ENABLED' ? '确认启用' : '确认禁用'"
    :loading="statusSaving"
    @submit="confirmToggleStatus"
  >
    <p v-if="statusError" class="error-text">{{ statusError }}</p>
    <p>确认{{ statusNext === 'ENABLED' ? '启用' : '禁用' }}用户「{{ statusTarget?.name }}（{{ statusTarget?.username }}）」？</p>
    <p v-if="statusNext === 'ENABLED'" class="hint">
      启用后可立即登录后台，权限沿用已分配的「{{ statusTarget?.role }}」角色；上次登录时间
      {{ statusTarget?.lastLogin }}。
    </p>
    <p v-else class="hint">
      禁用后该用户立即无法登录，在线会话失效；角色绑定与历史操作日志保留，可随时重新启用。
    </p>
  </FormDialog>

  <!-- 删除用户（D7-05 · dlg-user-del） -->
  <FormDialog
    v-model="delDialog"
    title="删除用户"
    icon="danger"
    width="is-sm"
    cancel-text="取消"
    confirm-text="确认删除"
    :loading="delSaving"
    @submit="confirmDelete"
  >
    <p v-if="delError" class="error-text">{{ delError }}</p>
    <p>确认删除用户「{{ delTarget?.name }}（{{ delTarget?.username }}）」？</p>
    <p class="hint">
      删除后其角色绑定一并解除，被删除的账号<b>不可恢复</b>；历史操作日志按审计要求保留。
    </p>
  </FormDialog>

  <!-- 超管不可删除（D7-06 · dlg-user-admin）/ 不能禁用自己（D7-07 · dlg-user-self） -->
  <NoticeDialog
    v-model="rejectDialog"
    :title="rejectTitle"
    icon="warn"
    width="is-sm"
    confirm-text="知道了"
  >
    <template v-if="rejectKind === 'selfDisable'">
      <p>「{{ rejectTarget?.username }}」是当前登录账号，不能禁用自己。</p>
      <p class="hint">
        禁用后你会立即失去后台访问权限且无法自行恢复。请改用其他管理员账号操作，或使用「重置密码」轮换本账号密码。
      </p>
    </template>
    <template v-else>
      <p>
        「{{ rejectTarget?.username }} · {{ rejectTarget?.name }}」是内置<b>超级管理员</b>账号，不允许{{
          rejectKind === 'superDelete' ? '删除' : '禁用'
        }}。
      </p>
      <p class="hint">
        超管账号受保护：既不能删除也不能禁用。如需停用请先新建一个超级管理员账号并转移职责。
      </p>
      <p v-if="rejectMsg" class="hint">{{ rejectMsg }}</p>
    </template>
  </NoticeDialog>
</template>

<style scoped>
.empty-cell {
  text-align: center;
  padding: 24px;
}
.input-readonly {
  background: var(--sand);
  color: var(--stone);
  cursor: not-allowed;
}
.role-box {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  padding: 9px 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--cream);
}
.role-box label {
  display: flex;
  align-items: center;
  gap: 6px;
}
.status-select {
  max-width: 180px;
}
.reset-meta {
  margin-bottom: 14px;
}
</style>