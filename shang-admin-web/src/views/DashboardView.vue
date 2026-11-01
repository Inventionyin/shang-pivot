<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import PageHead from '@/components/PageHead.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { fetchDashboardStats, fetchLowStockPreview } from '@/api/dashboard'
import { useAuthStore } from '@/stores/auth'
import { unwrapPage } from '@/utils/page'
import { stockTier, stockTierTone, type StockTier } from '@/utils/stock'

const auth = useAuthStore()

const stats = ref({
  spu: 0,
  sku: 0,
  lowStock: 0,
  pendingShip: 0,
})

const lowStockRows = ref<
  { sku: string; name: string; available: number; threshold: number; status: string }[]
>([])

const greeting = (() => {
  const h = new Date().getHours()
  if (h < 12) return '上午好'
  if (h < 18) return '下午好'
  return '晚上好'
})()

onMounted(async () => {
  try {
    const res = await fetchDashboardStats()
    const data = res.data as Record<string, number> | undefined
    if (data) {
      stats.value = {
        spu: Number(data.onShelfProducts ?? data.spu ?? 0),
        sku: Number(data.skuTotal ?? data.sku ?? 0),
        lowStock: Number(data.lowStockCount ?? data.lowStock ?? 0),
        pendingShip: Number(data.pendingShipOrders ?? data.pendingShip ?? 0),
      }
    }
  } catch {
    /* keep zeros */
  }
  try {
    const res = await fetchLowStockPreview()
    const pageData = unwrapPage<Record<string, unknown>>(res.data)
    if (pageData) {
      lowStockRows.value = pageData.records.map((r) => {
        const available = Number(r.availableStock ?? r.available ?? 0)
        const threshold = Number(r.lowThreshold ?? r.threshold ?? 0)
        return {
          sku: String(r.barCode ?? r.skuCode ?? r.skuId ?? '—'),
          name: String(r.skuTitle ?? r.name ?? r.title ?? '—'),
          available,
          threshold,
          status: stockTier(available, threshold),
        }
      })
    }
  } catch {
    lowStockRows.value = []
  }
})
</script>

<template>
  <PageHead
    :title="`${greeting}，${auth.user?.displayName || '管理员'}`"
    sub="Phase 1 供给侧工作台"
  >
    <template #actions>
      <RouterLink class="btn btn-secondary" to="/products/edit">去上架商品</RouterLink>
    </template>
  </PageHead>

  <div class="stats">
    <div class="stat">
      <div class="stat-label">在售商品（SPU）</div>
      <div class="stat-value">{{ stats.spu }}</div>
    </div>
    <div class="stat">
      <div class="stat-label">SKU 总数</div>
      <div class="stat-value">{{ stats.sku }}</div>
      <div class="stat-delta" style="color: var(--stone)">含 {{ stats.lowStock }} 个待补</div>
    </div>
    <div class="stat">
      <div class="stat-label">低库存预警</div>
      <div class="stat-value" style="color: var(--brick)">{{ stats.lowStock }}</div>
      <div class="stat-delta" style="color: var(--brick)">需补货</div>
    </div>
    <div class="stat">
      <div class="stat-label">待发货订单</div>
      <div class="stat-value">{{ stats.pendingShip }}</div>
    </div>
  </div>

  <div class="card card-pad">
    <div class="page-head" style="margin-bottom: 14px">
      <div class="page-title" style="font-size: 16px">库存预警（低库存 / 关注）</div>
      <RouterLink class="btn btn-ghost btn-sm" to="/inventory">全部库存 →</RouterLink>
    </div>
    <table class="table">
      <thead>
        <tr>
          <th class="code">SKU</th>
          <th>商品</th>
          <th class="num">可售</th>
          <th class="num">阈值</th>
          <th>状态</th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="!lowStockRows.length">
          <td colspan="5" class="hint" style="text-align: center; padding: 24px">暂无低库存预警</td>
        </tr>
        <tr v-for="row in lowStockRows" :key="row.sku">
          <td class="code">{{ row.sku }}</td>
          <td>{{ row.name }}</td>
          <td class="num">{{ row.available }}</td>
          <td class="num">{{ row.threshold }}</td>
          <td>
            <StatusBadge :text="row.status" :tone="stockTierTone(row.status as StockTier)" />
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
