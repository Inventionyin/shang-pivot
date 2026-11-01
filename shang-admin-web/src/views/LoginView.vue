<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const REMEMBER_KEY = 'shang_admin_remember'
const REMEMBER_USER_KEY = 'shang_admin_remember_username'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const username = ref('')
const password = ref('')
const remember = ref(true)
const loading = ref(false)

function loadRemembered() {
  try {
    const flag = localStorage.getItem(REMEMBER_KEY)
    remember.value = flag !== '0'
    if (remember.value) {
      username.value = localStorage.getItem(REMEMBER_USER_KEY) || ''
    }
  } catch {
    remember.value = true
  }
}

function persistRemember() {
  try {
    if (remember.value) {
      localStorage.setItem(REMEMBER_KEY, '1')
      localStorage.setItem(REMEMBER_USER_KEY, username.value.trim())
    } else {
      localStorage.setItem(REMEMBER_KEY, '0')
      localStorage.removeItem(REMEMBER_USER_KEY)
    }
  } catch {
    /* ignore quota / private mode */
  }
}

async function onSubmit() {
  if (!username.value || !password.value) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await auth.login({ username: username.value.trim(), password: password.value })
    persistRemember()
    const redirect = (route.query.redirect as string) || '/dashboard'
    await router.replace(redirect)
  } catch (e: unknown) {
    const err = e as { message?: string; msg?: string }
    ElMessage.error(err.msg || err.message || '登录失败')
  } finally {
    loading.value = false
  }
}

onMounted(loadRemembered)
</script>

<template>
  <div class="auth-wrap">
    <div class="auth-card">
      <div class="auth-logo">
        <img class="brand-logo" src="/logo.png" alt="Shang" />
      </div>
      <div class="auth-title">Shang 商城后台</div>
      <div class="auth-sub">Shang 电商平台 · 管理控制台</div>

      <form @submit.prevent="onSubmit">
        <div class="field">
          <label class="label">用户名</label>
          <input
            v-model="username"
            class="input"
            name="username"
            autocomplete="username"
            placeholder="请输入用户名"
            autofocus
          />
        </div>
        <div class="field">
          <label class="label">密码</label>
          <input
            v-model="password"
            class="input"
            type="password"
            name="password"
            autocomplete="current-password"
            placeholder="请输入密码"
          />
        </div>
        <div class="auth-row">
          <label class="remember">
            <input v-model="remember" type="checkbox" />
            记住我
          </label>
        </div>
        <button class="btn btn-primary auth-btn" type="submit" :disabled="loading">
          {{ loading ? '登录中…' : '登 录' }}
        </button>
      </form>
      <p class="hint" style="text-align: center; margin-top: 18px">连续失败 5 次将锁定 15 分钟</p>
    </div>
  </div>
</template>

<style scoped>
.auth-row {
  display: flex;
  align-items: center;
  margin-bottom: 20px;
}
.remember {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--stone);
  cursor: pointer;
  user-select: none;
}
</style>
