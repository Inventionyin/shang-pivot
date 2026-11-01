<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { toJsonDisplay } from '@/utils/jsonDisplay'

const props = withDefaults(
  defineProps<{
    value?: unknown
    title?: string
    maxHeight?: string
    /** 空值占位 */
    emptyText?: string
  }>(),
  {
    title: 'JSON',
    maxHeight: '280px',
    emptyText: '暂无数据',
  },
)

const copied = ref(false)

const display = computed(() => toJsonDisplay(props.value))

const bodyHtml = computed(() => {
  if (display.value.empty) return escapePlain(props.emptyText)
  return display.value.html
})

function escapePlain(s: string) {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

async function copy() {
  if (display.value.empty) return
  try {
    await navigator.clipboard.writeText(display.value.text)
    copied.value = true
    ElMessage.success('已复制')
    window.setTimeout(() => {
      copied.value = false
    }, 1500)
  } catch {
    ElMessage.error('复制失败')
  }
}
</script>

<template>
  <div class="json-viewer" :class="{ empty: display.empty, plain: !display.isJson && !display.empty }">
    <div class="json-viewer-bar">
      <span class="json-viewer-tag">{{ display.empty ? '空' : display.isJson ? title : '文本' }}</span>
      <button
        class="json-viewer-copy"
        type="button"
        :disabled="display.empty"
        @click="copy"
      >
        {{ copied ? '已复制' : '复制' }}
      </button>
    </div>
    <pre
      class="json-viewer-body"
      :style="{ maxHeight }"
      v-html="bodyHtml"
    />
  </div>
</template>
