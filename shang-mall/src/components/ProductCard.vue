<script setup lang="ts">
import { formatPrice } from '@/utils/format'
import { resolveMediaUrl } from '@/utils/media'
import type { ProductCard } from '@/api/catalog'

defineProps<{
  item: ProductCard
}>()
</script>

<template>
  <RouterLink class="product-card" :to="`/product/${item.spuId}`">
    <div class="product-card__cover">
      <img v-if="item.pic" :src="resolveMediaUrl(item.pic)" :alt="item.name" />
      <div v-else class="product-card__placeholder">暂无图片</div>
    </div>
    <div class="product-card__body">
      <div class="product-card__title">{{ item.name }}</div>
      <div class="product-card__meta">
        <span class="price">¥{{ formatPrice(item.price) }}</span>
        <span v-if="item.soldCount != null" class="sold">{{ item.soldCount }} 人付款</span>
      </div>
    </div>
  </RouterLink>
</template>

<style scoped>
.product-card {
  display: block;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 14px;
  overflow: hidden;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.product-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 20px rgba(36, 29, 21, 0.06);
}

.product-card__cover {
  aspect-ratio: 1;
  background: var(--sand);
}

.product-card__cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.product-card__placeholder {
  height: 100%;
  display: grid;
  place-items: center;
  color: var(--muted);
  font-size: 13px;
}

.product-card__body {
  padding: 10px 12px 12px;
}

.product-card__title {
  font-size: 14px;
  line-height: 1.35;
  height: 2.7em;
  overflow: hidden;
  margin-bottom: 8px;
}

.product-card__meta {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 8px;
}

.sold {
  font-size: 12px;
  color: var(--muted);
}
</style>
