<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { showConfirmDialog, showToast } from 'vant'
import { clearHistory, fetchHistory } from '@/api/customer'
import ProductCard from '@/components/ProductCard.vue'
import type { ProductCard as ProductCardType } from '@/api/catalog'

const loading = ref(true)
const records = ref<ProductCardType[]>([])

async function load() {
  loading.value = true
  try {
    const res = await fetchHistory({ page: 1, size: 50 })
    records.value = res.data.records || []
  } catch {
    records.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function onClear() {
  await showConfirmDialog({ title: '清空足迹', message: '确认清空浏览记录？' })
  await clearHistory()
  showToast('已清空')
  await load()
}
</script>

<template>
  <div class="page">
    <div class="head">
      <h1 class="page-title">浏览足迹</h1>
      <van-button v-if="records.length" size="small" plain @click="onClear">清空</van-button>
    </div>
    <van-skeleton v-if="loading" title :row="4" />
    <div v-else-if="records.length" class="card-grid">
      <ProductCard v-for="p in records" :key="String(p.spuId)" :item="p" />
    </div>
    <div v-else class="empty-hint">
      <strong>还没有足迹</strong>
      <RouterLink to="/category">去逛逛</RouterLink>
    </div>
  </div>
</template>

<style scoped>
.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
