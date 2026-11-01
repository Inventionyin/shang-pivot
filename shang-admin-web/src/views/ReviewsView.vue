<script setup lang="ts">
/**
 * 评价管理 —— 对齐原型 .stitch/原型/admin/reviews.html 与 dialogs-v2.html。
 * 弹窗：dlg-review-hide（D2V-10 隐藏评价）、dlg-review-restore（D2V-11 恢复评价）。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHead from '@/components/PageHead.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import ListPager from '@/components/ListPager.vue'
import FormDialog from '@/components/FormDialog.vue'
import { fetchReviews, updateReviewStatus } from '@/api/reviews'
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
  spuName: string
  customerPhone: string
  rating: number
  content: string
  status: string
  createdAt: string
}

const tab = ref('')
const keyword = ref('')
const rating = ref('')
const rows = ref<Row[]>([])

/** 隐藏 / 恢复（D2V-10 / D2V-11）：同一个确认弹窗，按当前可见性切标题与语义色 */
const statusDialog = ref(false)
const statusTarget = ref<Row | null>(null)
const statusSaving = ref(false)
const statusError = ref('')

const isVisible = computed(() => statusTarget.value?.status === 'VISIBLE')

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
    const isLow = rating.value === 'low'
    const res = await fetchReviews({
      status: tab.value || undefined,
      keyword: keyword.value.trim() || undefined,
      rating: !isLow && rating.value ? Number(rating.value) : undefined,
      ratingMax: isLow ? 2 : undefined,
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
    rows.value = pageData.records.map((r) => ({
      id: String(r.id ?? ''),
      spuName: String(r.spuName ?? r.spuId ?? '—'),
      customerPhone: String(r.customerPhone ?? '—'),
      rating: Number(r.rating ?? 0),
      content: String(r.content ?? ''),
      status: String(r.status ?? ''),
      createdAt: formatDateTime(r.createdAt),
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

function switchTab(t: string) {
  tab.value = t
  resetPage()
  return load()
}

function openStatus(row: Row) {
  if (!hasPermission('review:hide')) {
    ElMessage.warning('无权限')
    return
  }
  statusTarget.value = row
  statusError.value = ''
  statusDialog.value = true
}

async function confirmStatus() {
  const row = statusTarget.value
  if (!row) return
  const next = row.status === 'VISIBLE' ? 'HIDDEN' : 'VISIBLE'
  const label = next === 'HIDDEN' ? '隐藏' : '恢复'
  statusSaving.value = true
  statusError.value = ''
  try {
    await updateReviewStatus(row.id, next)
    ElMessage.success(`已${label}`)
    statusDialog.value = false
    await load()
  } catch (e: unknown) {
    statusError.value = readErrMsg(e) || `${label}失败，请稍后重试`
  } finally {
    statusSaving.value = false
  }
}

onMounted(load)
</script>

<template>
  <PageHead
    title="评价管理"
    sub="顾客订单评价 · 发表即展示 · 后台可隐藏 / 恢复 · 支持按商品、会员手机尾号与星级筛选"
  />

  <div class="card card-pad list-card">
    <div class="tabs">
      <div class="tab" :class="{ active: tab === '' }" @click="switchTab('')">全部</div>
      <div class="tab" :class="{ active: tab === 'VISIBLE' }" @click="switchTab('VISIBLE')">
        展示中
      </div>
      <div class="tab" :class="{ active: tab === 'HIDDEN' }" @click="switchTab('HIDDEN')">
        已隐藏
      </div>
    </div>

    <div class="toolbar">
      <input
        v-model="keyword"
        class="input"
        placeholder="商品名 / 会员手机尾号"
        @keyup.enter="search"
      />
      <select v-model="rating" class="select" @change="search">
        <option value="">全部星级</option>
        <option value="5">5 星</option>
        <option value="4">4 星</option>
        <option value="low">1～2 星（低星）</option>
        <option value="3">3 星</option>
        <option value="2">2 星</option>
        <option value="1">1 星</option>
      </select>
      <span class="hint">低星评价 = 1～2 星，用星级筛选直接筛出</span>
      <div class="toolbar-actions">
        <button
          class="btn btn-secondary btn-sm"
          type="button"
          @click="keyword = ''; rating = ''; search()"
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
            <th class="code">评价 ID</th>
            <th>商品</th>
            <th>会员</th>
            <th class="num">星级</th>
            <th>内容摘要</th>
            <th>可见性</th>
            <th class="code">发表时间</th>
            <th class="col-op">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!rows.length">
            <td colspan="8" class="hint empty-cell">暂无评价</td>
          </tr>
          <tr v-for="row in rows" :key="row.id">
            <td class="code">{{ row.id }}</td>
            <td>{{ row.spuName }}</td>
            <td>{{ row.customerPhone }}</td>
            <td class="num">{{ row.rating }}</td>
            <td>{{ row.content.slice(0, 40) }}{{ row.content.length > 40 ? '…' : '' }}</td>
            <td>
              <StatusBadge
                :text="row.status === 'VISIBLE' ? '展示' : '隐藏'"
                :tone="row.status === 'VISIBLE' ? 'forest' : 'brick'"
              />
            </td>
            <td class="code">{{ row.createdAt }}</td>
            <td class="col-op">
              <button
                v-if="hasPermission('review:hide')"
                class="btn btn-sm"
                :class="row.status === 'VISIBLE' ? 'btn-ghost' : 'btn-primary'"
                type="button"
                @click="openStatus(row)"
              >
                {{ row.status === 'VISIBLE' ? '隐藏' : '恢复' }}
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

  <!-- 隐藏评价（D2V-10 · dlg-review-hide）/ 恢复评价（D2V-11 · dlg-review-restore） -->
  <FormDialog
    v-model="statusDialog"
    :title="isVisible ? '隐藏评价' : '恢复评价'"
    :icon="isVisible ? 'danger' : 'ok'"
    :icon-text="isVisible ? '!' : '✓'"
    width="is-md"
    cancel-text="取消"
    :confirm-text="isVisible ? '确认隐藏' : '确认恢复'"
    :loading="statusSaving"
    @submit="confirmStatus"
  >
    <p v-if="statusError" class="error-text">{{ statusError }}</p>
    <p v-if="isVisible">确认隐藏评价「<b class="code">{{ statusTarget?.id }}</b>」？</p>
    <p v-else>确认恢复评价「<b class="code">{{ statusTarget?.id }}</b>」？</p>
    <ul class="impact">
      <li>商品：<b>{{ statusTarget?.spuName }}</b> · 会员：{{ statusTarget?.customerPhone }}</li>
      <li>星级：<b>{{ statusTarget?.rating }}</b> 星 · 发表时间：{{ statusTarget?.createdAt }}</li>
    </ul>
    <div v-if="isVisible" class="alert alert-warn impact-alert">
      隐藏后<b>商品详情不再展示</b>该条评价，也不再计入评分；评价数据与晒图保留，可随时恢复。本次操作会写入操作日志。
    </div>
    <div v-else class="alert alert-ok impact-alert">
      恢复后该评价<b>立即回到商品详情</b>并重新计入商品评分；顾客端刷新即可看到，无需重新审核。
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