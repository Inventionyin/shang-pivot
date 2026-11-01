<script setup lang="ts">
import { computed, ref } from 'vue'
import LoginForm from '@/components/LoginForm.vue'
import { useClientType } from '@/composables/useClientType'
import { loginLayerVisible, settleLoginLayer } from '@/composables/useLoginLayer'
import { useBrandStore } from '@/stores/brand'
import { useCartStore } from '@/stores/cart'

/**
 * 登录层（PRD §8.1 / §8.5 / §8.14）：未登录点加购、立即购买、结算、个人中心时拉起；
 * Web 居中对话框，H5 底部半屏；登录成功后回到原场景。
 */
const { isH5 } = useClientType()
const brand = useBrandStore()
const cart = useCartStore()

const formRef = ref<InstanceType<typeof LoginForm> | null>(null)

const show = computed({
  get: () => loginLayerVisible.value,
  set: (v: boolean) => {
    if (!v) settleLoginLayer(false)
  },
})

const popupStyle = { background: 'transparent' }
const title = computed(() => `登录${brand.displayName || '尚品汇'}`)

async function onSuccess() {
  await cart.refresh()
  settleLoginLayer(true)
}

function onCancel() {
  settleLoginLayer(false)
}
</script>

<template>
  <van-popup
    v-model:show="show"
    teleport="body"
    :position="isH5 ? 'bottom' : 'center'"
    :round="isH5"
    :safe-area-inset-bottom="isH5"
    :style="popupStyle"
  >
    <div class="pop-panel" :class="isH5 ? 'is-sheet' : 'is-dialog'">
      <div v-if="isH5" class="sheet-grab" />
      <div class="pop-head">
        <span class="pop-title">{{ title }}</span>
        <button type="button" class="pop-x" aria-label="关闭" @click="onCancel">
          <svg class="ico" viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18" /></svg>
        </button>
      </div>

      <div class="pop-body">
        <LoginForm ref="formRef" intro @success="onSuccess" />
      </div>

      <div class="pop-foot">
        <button type="button" class="btn btn-secondary" @click="onCancel">取消</button>
        <button
          type="button"
          class="btn btn-primary"
          :disabled="formRef?.submitting"
          @click="formRef?.submit()"
        >
          登录
        </button>
      </div>
    </div>
  </van-popup>
</template>