<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import LoginForm from '@/components/LoginForm.vue'
import { useBrandStore } from '@/stores/brand'
import { useCartStore } from '@/stores/cart'

/** 独立登录页（原型 store/web/login.html / store/h5/login.html），与登录层共用表单 */
const route = useRoute()
const router = useRouter()
const brand = useBrandStore()
const cart = useCartStore()

const formRef = ref<InstanceType<typeof LoginForm> | null>(null)

async function onSuccess() {
  await cart.refresh()
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
  await router.replace(redirect || '/')
}
</script>

<template>
  <div class="login page">
    <div class="panel">
      <img :src="brand.logoUrl" alt="" class="logo" />
      <h1>登录{{ brand.displayName }}</h1>
      <p class="page-desc">{{ brand.slogan }}</p>

      <LoginForm ref="formRef" @success="onSuccess" />

      <button
        type="button"
        class="btn btn-primary btn-block submit"
        :disabled="formRef?.submitting"
        @click="formRef?.submit()"
      >
        {{ formRef?.submitting ? '登录中…' : '登录' }}
      </button>
      <p class="tip">登录即表示同意用户协议与隐私政策</p>
      <p class="tip">游客可浏览首页、分类、详情与搜索；加购、下单、个人中心需登录</p>
    </div>
  </div>
</template>

<style scoped>
.login {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding-bottom: 16px;
}

.panel {
  width: min(420px, 100%);
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 20px;
  padding: 28px 20px;
}

.logo {
  width: 56px;
  height: 56px;
  object-fit: contain;
  margin-bottom: 12px;
}

h1 {
  margin: 0 0 4px;
  font-size: 24px;
}

.submit {
  margin-top: 20px;
}

.tip {
  margin: 12px 0 0;
  color: var(--muted);
  font-size: 12px;
  text-align: center;
}
</style>