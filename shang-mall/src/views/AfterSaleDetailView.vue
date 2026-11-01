<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { fetchAfterSaleDetail } from '@/api/order'
import { formatPrice } from '@/utils/format'

const route = useRoute()
const id = String(route.params.id || '')
const loading = ref(true)
const afterSale = ref<Record<string, unknown> | null>(null)
const order = ref<Record<string, unknown> | null>(null)

onMounted(async () => {
  try {
    const res = await fetchAfterSaleDetail(id)
    afterSale.value = res.data.afterSale
    order.value = res.data.order
  } catch {
    afterSale.value = null
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="page">
    <h1 class="page-title">售后详情</h1>
    <van-skeleton v-if="loading" title :row="5" />
    <template v-else-if="afterSale">
      <div class="panel">
        <div>单号 {{ afterSale.afterSaleNo }}</div>
        <div>状态 {{ afterSale.status }}</div>
        <div>类型 {{ afterSale.type }}</div>
        <div>原因 {{ afterSale.reason }}</div>
        <div>退款 ¥{{ formatPrice(afterSale.refundAmount as number) }}</div>
        <div v-if="afterSale.auditRemark">处理意见 {{ afterSale.auditRemark }}</div>
        <div v-if="order">关联订单 {{ order.orderNo }}</div>
      </div>
    </template>
    <div v-else class="empty-hint">售后单不存在</div>
  </div>
</template>

<style scoped>
.panel {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 14px;
  padding: 16px;
  display: grid;
  gap: 8px;
}
</style>
