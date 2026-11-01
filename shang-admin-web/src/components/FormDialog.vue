<script setup lang="ts">
/**
 * 表单弹窗 —— 全后台统一形态（对应原型 .stitch/原型/admin/modal.css 的 .modal）。
 *
 * 用法：
 *   <FormDialog v-model="open" :title="editing ? '编辑类目' : '新增类目'" icon="info"
 *               :width="'is-md'" :loading="saving" @submit="submit">
 *     <div class="field">…</div>
 *   </FormDialog>
 *
 * 约定（PRD-V1.0 §8.18）：字段级校验错误用 .error-text 内联在控件下方，
 * 不要用 ElMessage 顶部提示替代；提交中 loading 防重复。
 */
import { watch } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    title: string
    /** 头部语义图标：info 中性 / warn 警示 / danger 危险 / ok 成功 */
    icon?: 'info' | 'warn' | 'danger' | 'ok'
    iconText?: string
    /** 宽度档：is-sm 380 / is-md 440 / is-lg 620 / is-xl 820 */
    width?: 'is-sm' | 'is-md' | 'is-lg' | 'is-xl'
    confirmText?: string
    cancelText?: string
    /** 纯提示弹窗：只留一个确认按钮 */
    noticeOnly?: boolean
    loading?: boolean
    /** 提交中/纯提示时点击遮罩是否可关 */
    closeOnMask?: boolean
  }>(),
  {
    icon: 'info',
    iconText: '',
    width: 'is-md',
    confirmText: '保存',
    cancelText: '取消',
    noticeOnly: false,
    loading: false,
    closeOnMask: true,
  },
)

const emit = defineEmits<{
  'update:modelValue': [boolean]
  submit: []
  cancel: []
}>()

const ICON_TEXT: Record<string, string> = { info: '✓', warn: '!', danger: '×', ok: '✓' }

function close() {
  emit('update:modelValue', false)
  emit('cancel')
}

function onMask() {
  if (!props.closeOnMask || props.loading) return
  close()
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') onMask()
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
  <div v-if="modelValue" class="modal" role="dialog" aria-modal="true">
    <div class="modal-mask-layer" @click="onMask" />
    <div class="modal-panel" :class="width">
      <div class="modal-head">
        <span v-if="icon" class="modal-ico" :class="icon">
          {{ iconText || ICON_TEXT[icon] }}
        </span>
        <div class="modal-title">{{ title }}</div>
        <button class="modal-x" type="button" aria-label="关闭" @click="close">×</button>
      </div>

      <div class="modal-body">
        <slot />
      </div>

      <div class="modal-foot">
        <button
          v-if="!noticeOnly"
          class="btn btn-secondary"
          type="button"
          :disabled="loading"
          @click="close"
        >
          {{ cancelText }}
        </button>
        <button
          class="btn"
          :class="icon === 'danger' ? 'btn-danger' : 'btn-primary'"
          type="button"
          :disabled="loading"
          @click="emit('submit')"
        >
          {{ loading ? '处理中…' : confirmText }}
        </button>
      </div>
    </div>
  </div>
</template>