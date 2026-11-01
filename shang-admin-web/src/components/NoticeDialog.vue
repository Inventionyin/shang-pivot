<script setup lang="ts">
/**
 * 拒绝 / 提示弹窗 —— 只有一个「知道了」，不给「取消」。
 * 用途：状态不允许、被引用不可删、校验未通过等（原型 dialogs.html 的 D2-04 / D5-02 / D7-06 等）。
 * 不给取消按钮是有意的：让用户明白这不是「重试就能过」的确认框。
 */
withDefaults(
  defineProps<{
    modelValue: boolean
    title: string
    /** warn 业务不允许 / danger 破坏性被拒 / ok 成功告知 */
    icon?: 'warn' | 'danger' | 'ok' | 'info'
    iconText?: string
    width?: 'is-sm' | 'is-md' | 'is-lg'
    confirmText?: string
  }>(),
  { icon: 'warn', iconText: '', width: 'is-sm', confirmText: '知道了' },
)

const emit = defineEmits<{ 'update:modelValue': [boolean] }>()

const ICON_TEXT: Record<string, string> = { info: '✓', warn: '!', danger: '×', ok: '✓' }
</script>

<template>
  <div v-if="modelValue" class="modal" role="alertdialog" aria-modal="true">
    <div class="modal-mask-layer" @click="emit('update:modelValue', false)" />
    <div class="modal-panel" :class="width">
      <div class="modal-head">
        <span class="modal-ico" :class="icon">{{ iconText || ICON_TEXT[icon] }}</span>
        <div class="modal-title">{{ title }}</div>
        <button class="modal-x" type="button" aria-label="关闭" @click="emit('update:modelValue', false)">
          ×
        </button>
      </div>
      <div class="modal-body">
        <slot />
      </div>
      <div class="modal-foot">
        <button class="btn btn-primary" type="button" @click="emit('update:modelValue', false)">
          {{ confirmText }}
        </button>
      </div>
    </div>
  </div>
</template>