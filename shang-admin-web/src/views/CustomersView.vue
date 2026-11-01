<script setup lang="ts">
/**
 * 会员管理 —— 对齐原型 .stitch/原型/admin/customers.html 与 dialogs-v2.html。
 * 弹窗：dlg-cust-freeze（D2V-01 冻结会员）、dlg-cust-unfreeze（D2V-02 解冻会员）。
 * 「订单」是链接（原型 <a href="orders.html">），不弹窗。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHead from '@/components/PageHead.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import ListPager from '@/components/ListPager.vue'
import FormDialog from '@/components/FormDialog.vue'
import { fetchCustomers, updateCustomerStatus } from '@/api/customers'
import { usePager } from '@/composables/usePager'
import { unwrapPage } from '@/utils/page'
import { formatDateTime } from '@/utils/datetime'
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

type Row = {
  id: string
  phone: string
  nickname: string
  status: string
  orderCount: number
  createdAt: string
  lastLoginAt: string
}

const keyword = ref('')
const status = ref('')
const clientType = ref('')
const rows = ref<Row[]>([])

/** 冻结 / 解冻（D2V-01 / D2V-02）：同一个确认弹窗，按目标会员状态切标题与语义色 */
const toggleDialog = ref(false)
const toggleTarget = ref<Row | null>(null)
const toggleSaving = ref(false)
const toggleError = ref('')

const isFrozen = computed(() => toggleTarget.value?.status === 'FROZEN')
const memberLine = computed(
  () => `${toggleTarget.value?.nickname ?? '—'} · ${toggleTarget.value?.phone ?? '—'}`,
)

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
    const kw = keyword.value.trim()
    const digitsOnly = /^\d+$/.test(kw)
    const res = await fetchCustomers({
      phone: digitsOnly ? kw : undefined,
      nickname: !digitsOnly && kw ? kw : undefined,
      status: status.value || undefined,
      clientType: clientType.value || undefined,
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
    rows.value = pageData.records.map((c) => ({
      id: String(c.id ?? ''),
      phone: String(c.phone ?? '—'),
      nickname: String(c.nickname ?? '—'),
      status: String(c.status ?? ''),
      orderCount: Number(c.orderCount ?? 0),
      createdAt: formatDateTime(c.createdAt),
      lastLoginAt: formatDateTime(c.lastLoginAt) || '—',
    }))
  } catch {
    rows.value = []
    total.value = 0
  }
}

function search() {
  resetPage()
  return load()
}

function ordersTo(row: Row) {
  return { path: '/orders', query: { phoneTail: row.phone.replace(/\D/g, '').slice(-4) } }
}

function openToggle(row: Row) {
  if (!hasPermission('customer:freeze')) {
    ElMessage.warning('无权限')
    return
  }
  toggleTarget.value = row
  toggleError.value = ''
  toggleDialog.value = true
}

async function confirmToggle() {
  const row = toggleTarget.value
  if (!row) return
  const next = row.status === 'FROZEN' ? 'NORMAL' : 'FROZEN'
  const label = next === 'FROZEN' ? '冻结' : '解冻'
  toggleSaving.value = true
  toggleError.value = ''
  try {
    await updateCustomerStatus(row.id, next)
    ElMessage.success(`已${label}`)
    toggleDialog.value = false
    await load()
  } catch (e: unknown) {
    toggleError.value = readErrMsg(e) || `${label}失败，请稍后重试`
  } finally {
    toggleSaving.value = false
  }
}

onMounted(load)
</script>

<template>
  <PageHead
    title="会员管理"
    sub="C 端顾客账号 · 手机号脱敏展示 · 冻结后不可登录与下单 · 可跳转查看该会员订单"
  />

  <div class="card card-pad list-card">
    <div class="toolbar">
      <input
        v-model="keyword"
        class="input"
        placeholder="手机号 / 昵称"
        @keyup.enter="search"
      />
      <select v-model="status" class="select" @change="search">
        <option value="">全部状态</option>
        <option value="NORMAL">正常</option>
        <option value="FROZEN">已冻结</option>
      </select>
      <select v-model="clientType" class="select" @change="search">
        <option value="">全部端</option>
        <option value="web">Web</option>
        <option value="h5">H5</option>
      </select>
      <div class="toolbar-actions">
        <button
          class="btn btn-secondary btn-sm"
          type="button"
          @click="keyword = ''; status = ''; clientType = ''; search()"
        >
          重置
        </button>
        <button class="btn btn-primary btn-sm" type="button" @click="search">查询</button>
      </div>
    </div>

    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th class="code">会员 ID</th>
            <th class="code">手机号</th>
            <th>昵称</th>
            <th>状态</th>
            <th class="num">订单数</th>
            <th class="code">注册时间</th>
            <th class="code">最近登录</th>
            <th class="col-op">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!rows.length">
            <td colspan="8" class="hint empty-cell">暂无会员</td>
          </tr>
          <tr v-for="row in rows" :key="row.id">
            <td class="code">{{ row.id }}</td>
            <td class="code">{{ row.phone }}</td>
            <td>{{ row.nickname }}</td>
            <td>
              <StatusBadge
                :text="row.status === 'FROZEN' ? '已冻结' : '正常'"
                :tone="row.status === 'FROZEN' ? 'brick' : 'forest'"
              />
            </td>
            <td class="num">{{ row.orderCount }}</td>
            <td class="code">{{ row.createdAt }}</td>
            <td class="code">{{ row.lastLoginAt }}</td>
            <td class="col-op">
              <router-link class="btn btn-ghost btn-sm" :to="ordersTo(row)">订单</router-link>
              <button
                v-if="hasPermission('customer:freeze')"
                class="btn btn-sm"
                :class="row.status === 'FROZEN' ? 'btn-primary' : 'btn-ghost'"
                type="button"
                @click="openToggle(row)"
              >
                {{ row.status === 'FROZEN' ? '解冻' : '冻结' }}
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

  <!-- 冻结会员（D2V-01 · dlg-cust-freeze）/ 解冻会员（D2V-02 · dlg-cust-unfreeze） -->
  <FormDialog
    v-model="toggleDialog"
    :title="isFrozen ? '解冻会员' : '冻结会员'"
    :icon="isFrozen ? 'ok' : 'danger'"
    :icon-text="isFrozen ? '✓' : '!'"
    width="is-md"
    cancel-text="取消"
    :confirm-text="isFrozen ? '确认解冻' : '确认冻结'"
    :loading="toggleSaving"
    @submit="confirmToggle"
  >
    <p v-if="toggleError" class="error-text">{{ toggleError }}</p>
    <p v-if="isFrozen">确认解冻会员「{{ memberLine }}」？</p>
    <p v-else>确认冻结会员「{{ memberLine }}」？</p>
    <ul v-if="isFrozen" class="impact">
      <li>恢复<b>登录</b>与<b>下单</b>能力，登录态需重新登录获取</li>
      <li>购物车、收藏、足迹等账户数据沿用冻结前状态</li>
      <li>冻结期间的订单与评价记录不受影响</li>
    </ul>
    <ul v-else class="impact">
      <li>立即<b>不能登录</b>购物端：Web / H5 登录态同时失效</li>
      <li>立即<b>不能下单</b>与提交售后；购物车、收藏、足迹保留</li>
      <li>已生成订单不受影响，可继续发货与售后</li>
    </ul>
    <div v-if="isFrozen" class="alert alert-ok impact-alert">
      解冻后顾客即可正常使用购物端；本次操作会写入操作日志（操作人、时间、会员 ID）。
    </div>
    <div v-else class="alert alert-warn impact-alert">
      冻结为可逆操作：历史订单与评价记录全部保留，可随时在「会员管理」中解冻。
    </div>
  </FormDialog>
</template>

<style scoped>
.empty-cell {
  text-align: center;
  padding: 24px;
}
.impact-alert {
  margin-top: 12px;
}
</style>