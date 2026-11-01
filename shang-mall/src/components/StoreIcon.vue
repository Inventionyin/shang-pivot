<script setup lang="ts">
/**
 * C 端单色描边图标集（设计系统 §3：stroke-width: 2，不用 emoji 当图标）。
 * 路径取自 `.stitch/原型/store/**` 的 .ico 约定，24×24 viewBox。
 */
import type { IconName } from '@/components/storeIcons'

withDefaults(defineProps<{ name: IconName; size?: number }>(), { size: 18 })
</script>

<template>
  <svg
    class="ico"
    viewBox="0 0 24 24"
    aria-hidden="true"
    :style="{ width: `${size}px`, height: `${size}px` }"
  >
    <template v-if="name === 'clock'">
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5.5l3.5 2" />
    </template>
    <template v-else-if="name === 'box'">
      <path d="M6 3h9l4 4v14H6z" />
      <path d="M15 3v4h4" />
    </template>
    <template v-else-if="name === 'truck'">
      <path d="M2 6h12v10H2z" />
      <path d="M14 9h4l4 4v3h-8z" />
      <circle cx="7" cy="18.5" r="1.6" />
      <circle cx="18" cy="18.5" r="1.6" />
    </template>
    <template v-else-if="name === 'star'">
      <path d="M12 3l2.6 5.3 5.9.8-4.3 4.1 1 5.8-5.2-2.8-5.2 2.8 1-5.8L3.5 9.1l5.9-.8L12 3z" />
    </template>
    <template v-else-if="name === 'refund'">
      <path d="M4 10h11a5 5 0 010 10H8" />
      <path d="M8 6l-4 4 4 4" />
    </template>
    <template v-else-if="name === 'heart'">
      <path d="M12 20s-7-4.5-7-9.5A4 4 0 0112 7a4 4 0 017 3.5C19 15.5 12 20 12 20z" />
    </template>
    <template v-else-if="name === 'eye'">
      <path d="M2 12s3.6-6 10-6 10 6 10 6-3.6 6-10 6-10-6-10-6z" />
      <circle cx="12" cy="12" r="3" />
    </template>
    <template v-else-if="name === 'pin'">
      <path d="M12 21s7-6.1 7-11a7 7 0 10-14 0c0 4.9 7 11 7 11z" />
      <circle cx="12" cy="10" r="2.5" />
    </template>
    <template v-else-if="name === 'settings'">
      <path d="M4 7h11" />
      <path d="M19 7h1" />
      <circle cx="17" cy="7" r="2" />
      <path d="M4 17h5" />
      <path d="M13 17h7" />
      <circle cx="11" cy="17" r="2" />
    </template>
    <template v-else-if="name === 'logout'">
      <path d="M15 4h3a2 2 0 012 2v12a2 2 0 01-2 2h-3" />
      <path d="M10 8l-4 4 4 4" />
      <path d="M6 12h10" />
    </template>
    <template v-else-if="name === 'cart'">
      <circle cx="9" cy="20" r="1.5" />
      <circle cx="18" cy="20" r="1.5" />
      <path d="M2 3h3l2.5 12h12L22 7H6" />
    </template>
    <template v-else-if="name === 'user'">
      <circle cx="12" cy="8" r="4" />
      <path d="M4 21a8 8 0 0116 0" />
    </template>
    <template v-else-if="name === 'trash'">
      <path d="M4 7h16" />
      <path d="M9 7V4h6v3" />
      <path d="M6 7l1 14h10l1-14" />
    </template>
    <template v-else-if="name === 'alert'">
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7.5v5.5M12 16.5v.01" />
    </template>
    <template v-else-if="name === 'check'">
      <path d="M4 12l5 5L20 6" />
    </template>
    <template v-else-if="name === 'close'">
      <path d="M6 6l12 12M18 6L6 18" />
    </template>
    <template v-else-if="name === 'search'">
      <circle cx="11" cy="11" r="7" />
      <path d="M20 20l-4.2-4.2" />
    </template>
    <template v-else-if="name === 'plus'">
      <path d="M12 6v12M6 12h12" />
    </template>
    <template v-else-if="name === 'arrow-up'">
      <path d="M12 19V5" />
      <path d="M6 11l6-6 6 6" />
    </template>
    <template v-else-if="name === 'arrow-down'">
      <path d="M12 5v14" />
      <path d="M18 13l-6 6-6-6" />
    </template>
  </svg>
</template>