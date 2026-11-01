<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchAfterSales } from '@/api/order'

const loading = ref(true)
const records = ref<Array<Record<string, unknown>>>([])

onMounted(async () => {
  try {
    const res = await fetchAfterSales({ page: 1, size: 20 })
    records.value = (res.data.records as Array<Record<string, unknown>>) || []
  } catch {
    records.value = []
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="page">
    <h1 class="page-title">我的售后</h1>
    <van-skeleton v-if="loading" title :row="4" />
    <div v-else-if="!records.length" class="empty-hint">
      <strong>暂无售后单</strong>
      可从订单详情发起申请
    </div>
    <RouterLink
      v-for="a in records"
      :key="String(a.id)"
      class="card"
      :to="`/after-sales/${a.id}`"
    >
      <div class="row">
        <span>{{ a.afterSaleNo }}</span>
        <span class="status">{{ a.status }}</span>
      </div>
      <div class="muted">{{ a.type }} · {{ a.reason }}</div>
    </RouterLink>
  </div>
</template>

<style scoped>
.card {
  display: block;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 14px;
  padding: 14px;
  margin-bottom: 10px;
}

.row {
  display: flex;
  justify-content: space-between;
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
