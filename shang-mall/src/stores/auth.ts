import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  fetchMe,
  loginByEmail as loginByEmailApi,
  loginByPhone,
  logoutApi,
  type CustomerProfile,
} from '@/api/auth'
import { clearTokens, getAccessToken, setTokens } from '@/utils/request'
import { clearChatTestMode } from '@/utils/chatStorage'

export const useAuthStore = defineStore('auth', () => {
  const customer = ref<CustomerProfile | null>(null)
  const bootstrapped = ref(false)

  const isLoggedIn = computed(() => !!getAccessToken() && !!customer.value)

  async function bootstrap() {
    if (!getAccessToken()) {
      customer.value = null
      bootstrapped.value = true
      return
    }
    try {
      const res = await fetchMe()
      customer.value = res.data
    } catch {
      clearTokens()
      customer.value = null
    } finally {
      bootstrapped.value = true
    }
  }

  async function login(phone: string, code: string) {
    const res = await loginByPhone(phone, code)
    clearChatTestMode()
    setTokens(res.data.accessToken, res.data.refreshToken)
    customer.value = res.data.customer
    return res.data
  }

  async function loginByEmail(email: string, code: string) {
    const res = await loginByEmailApi(email, code)
    clearChatTestMode()
    setTokens(res.data.accessToken, res.data.refreshToken)
    customer.value = res.data.customer
    return res.data
  }

  async function logout() {
    try {
      await logoutApi()
    } catch {
      /* ignore */
    }
    clearTokens()
    clearChatTestMode()
    customer.value = null
  }

  return { customer, bootstrapped, isLoggedIn, bootstrap, login, loginByEmail, logout }
})
