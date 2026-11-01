<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog } from 'vant'
import StoreIcon from '@/components/StoreIcon.vue'
import { useAuthStore } from '@/stores/auth'
import { useCartStore } from '@/stores/cart'
import { useClientType } from '@/composables/useClientType'
import { maskPhone } from '@/utils/format'

/** PRD §8.10.5：设置 —— 退出登录（清除本端登录态） */
const router = useRouter()
const auth = useAuthStore()
const cart = useCartStore()
const { isH5 } = useClientType()

const phoneText = computed(() => maskPhone(auth.customer?.phone))

async function onLogout() {
  await showConfirmDialog({
    title: '退出登录？',
    message: '退出后需重新用手机号验证码登录，本端登录态会被清除；另一端不受影响。',
  })
  await auth.logout()
  cart.reset()
  await router.replace('/login')
}
</script>

<template>
  <div class="page" :class="{ 'settings--h5': isH5 }">
    <h1 class="page-title">设置</h1>

    <div class="card">
      <div class="row">
        <span class="k">登录手机号</span>
        <span class="v">{{ phoneText || '—' }}</span>
      </div>
      <div class="row">
        <span class="k">登录态</span>
        <span class="v">Web 与 H5 各自独立</span>
      </div>
    </div>

    <button type="button" class="logout" @click="onLogout">
      <StoreIcon name="logout" :size="16" />
      退出登录
    </button>
    <p class="hint foot">退出仅清除当前端登录态，另一端不受影响</p>
  </div>
</template>

<style scoped>
.card {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  padding: 4px 16px;
}

.row {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 13px 0;
  font-size: 14px;
  border-bottom: 1px solid var(--border);
}

.row:last-child {
  border-bottom: 0;
}

.k {
  color: var(--stone);
}

.v {
  text-align: right;
}

.logout {
  width: 100%;
  margin-top: 16px;
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  color: var(--ink);
  font-weight: 600;
  cursor: pointer;
}

.logout:hover {
  border-color: var(--brick);
  color: var(--brick);
}

.foot {
  margin-top: 12px;
}

@media (min-width: 768px) {
  .logout {
    max-width: 200px;
  }
}
</style>