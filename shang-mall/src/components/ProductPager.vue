<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    page: number
    total: number
    pageSize?: number
  }>(),
  { pageSize: 20 },
)

const emit = defineEmits<{
  'update:page': [page: number]
}>()

const pageCount = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)))
const multiPage = computed(() => props.total > props.pageSize)

function onChange(p: number) {
  emit('update:page', p)
}
</script>

<template>
  <div v-if="total > 0" class="product-pager">
    <van-pagination
      v-if="multiPage"
      :model-value="page"
      :total-items="total"
      :items-per-page="pageSize"
      :show-page-size="5"
      force-ellipses
      @update:model-value="onChange"
    />
    <p class="product-pager__meta">
      共 {{ total }} 件
      <template v-if="multiPage"> · {{ page }}/{{ pageCount }} 页</template>
    </p>
  </div>
</template>

<style scoped>
.product-pager {
  margin-top: 20px;
  padding: 12px 0 4px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.product-pager__meta {
  margin: 0;
  font-size: 12px;
  color: var(--muted);
}

:deep(.van-pagination) {
  --van-pagination-height: 32px;
  --van-pagination-font-size: 13px;
  --van-pagination-item-default-color: var(--ink);
  --van-pagination-background: var(--sand);
  --van-pagination-item-width: 32px;
}
</style>
