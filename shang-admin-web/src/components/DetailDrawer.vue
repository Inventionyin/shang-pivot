<script setup lang="ts">
/**
 * 详情抽屉 —— 右侧滑出，对应原型 .stitch/原型/admin/modal.css 的 .drawer。
 * 订单详情 / 售后详情 / 日志详情 / 出入库单据详情统一用它（PRD-V1.0 §8.13 §8.14 §8.7）。
 */
import { watch } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    title: string
    /** 标题右侧的状态徽标文案（可选） */
    badge?: string
    badgeTone?: 'amber' | 'coral' | 'forest' | 'brick'
  }>(),
  { badge: '', badgeTone: 'amber' },
)

const emit = defineEmits<{ 'update:modelValue': [boolean] }>()

function close() {
  emit('update:modelValue', false)
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') close()
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) document.addEventListener('keydown', onKey)
    else document.removeEventListener('keydown', onKey)
    document.body.style.overflow = open ? 'hidden' : ''
  },
)
</script>

<template>
  <div v-if="modelValue" class="drawer" role="dialog" aria-modal="true">
    <div class="modal-mask-layer" @click="close" />
    <div class="drawer-panel">
      <div class="modal-head">
        <div class="modal-title">{{ title }}</div>
        <span v-if="badge" class="badge" :class="`badge-${badgeTone}`">{{ badge }}</span>
        <button class="modal-x" type="button" aria-label="关闭" @click="close">×</button>
      </div>

      <div class="modal-body">
        <slot />
      </div>

      <div v-if="$slots.footer" class="modal-foot">
        <slot name="footer" :close="close" />
      </div>
    </div>
  </div>
</template>