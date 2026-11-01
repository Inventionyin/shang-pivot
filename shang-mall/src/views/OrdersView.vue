<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchOrders } from '@/api/order'
import { formatPrice } from '@/utils/format'

const STATUS_TABS = [
  { name: '', title: '全部' },
  { name: 'PENDING_PAY', title: '待付款' },
  { name: 'PENDING_SHIP', title: '待发货' },
  { name: 'PENDING_RECEIVE', title: '待收货' },
  { name: 'COMPLETED', title: '已完成' },
] as const

const STATUS_LABEL: Record<string, string> = {
  PENDING_PAY: '待付款',
  PENDING_SHIP: '待发货',
  PENDING_RECEIVE: '待收货',
  COMPLETED: '已完成',
  CLOSED: '已关闭',
}

const route = useRoute()
const router = useRouter()
const loading = ref(true)
const records = ref<Array<Record<string, unknown>>>([])
const active = ref(String(route.query.status || ''))

async function load() {
  loading.value = true
  try {
    const res = await fetchOrders({
      status: active.value || undefined,
      page: 1,
      size: 50,
    })
    records.value = (res.data.records as Array<Record<string, unknown>>) || []
  } catch {
    records.value = []
  } finally {
    loading.value = false
  }
}

function onTab(name: string | number) {
  const next = String(name)
  active.value = next
  void router.replace({
    path: '/orders',
    query: next ? { status: next } : {},
  })
}

onMounted(() => {
  void load()
})

watch(
  () => route.query.status,
  (v) => {
    active.value = String(v || '')
    void load()
  },
)
</script>

<template>
  <div class="page">
    <h1 class="page-title">我的订单</h1>
    <van-tabs :active="active" shrink @change="onTab">
      <van-tab
        v-for="t in STATUS_TABS"
        :key="t.name || 'all'"
        :title="t.title"
        :name="t.name"
      />
    </van-tabs>

    <van-skeleton v-if="loading" title :row="5" />
    <div v-else-if="!records.length" class="empty-hint">
      <strong>暂无订单</strong>
      <RouterLink to="/category">去逛逛</RouterLink>
    </div>
    <RouterLink
      v-for="o in records"
      :key="String(o.id || o.orderId)"
      class="order-card"
      :to="`/orders/${o.id || o.orderId}`"
    >
      <div class="row">
        <span>{{ o.orderNo || o.id }}</span>
        <span class="status">{{ STATUS_LABEL[String(o.status)] || o.status }}</span>
      </div>
      <div class="row muted">
        <span>实付</span>
        <span class="price">¥{{ formatPrice(o.payAmount as number) }}</span>
      </div>
    </RouterLink>
  </div>
</template>

<style scoped>
.order-card {
  display: block;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 14px;
  padding: 14px;
  margin-bottom: 10px;
  margin-top: 10px;
}

.row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 6px;
}

.status {
  color: var(--coral);
  font-size: 13px;
}

.muted {
  color: var(--muted);
  font-size: 13px;
}
</style>
