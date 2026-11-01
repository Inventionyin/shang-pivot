<script setup lang="ts">
defineProps<{
  total: number
  page: number
  totalPages: number
  pageSize: number
  canPrev: boolean
  canNext: boolean
}>()

const emit = defineEmits<{
  prev: []
  next: []
}>()
</script>

<template>
  <div class="pager pager-fixed">
    <span>共 {{ total }} 条 · 每页 {{ pageSize }} 条</span>
    <button
      class="btn btn-secondary btn-sm"
      type="button"
      :disabled="!canPrev"
      @click="emit('prev')"
    >
      ‹
    </button>
    <span class="badge badge-amber">{{ page }} / {{ totalPages }}</span>
    <button
      class="btn btn-secondary btn-sm"
      type="button"
      :disabled="!canNext"
      @click="emit('next')"
    >
      ›
    </button>
  </div>
</template>
