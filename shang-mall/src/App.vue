<script setup lang="ts">
import { onMounted } from 'vue'
import LoginLayer from '@/components/LoginLayer.vue'
import { useBrandStore } from '@/stores/brand'
import { useAuthStore } from '@/stores/auth'
import { useCartStore } from '@/stores/cart'
import { getAccessToken } from '@/utils/request'

const brand = useBrandStore()
const auth = useAuthStore()
const cart = useCartStore()

onMounted(async () => {
  await Promise.all([brand.load(), auth.bootstrap()])
  if (getAccessToken()) {
    await cart.refresh()
  }
})
</script>

<template>
  <RouterView />
  <!-- 登录层：全局挂载，任意页面可拉起（PRD §8.1） -->
  <LoginLayer />
</template>
