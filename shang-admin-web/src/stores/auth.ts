import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  fetchMe,
  login as loginApi,
  logout as logoutApi,
  type LoginPayload,
  type MenuNode,
} from '@/api/auth'
import { clearToken, getToken, setToken } from '@/utils/request'
import { useTabsStore } from '@/stores/tabs'

const USER_KEY = 'shang_admin_user'
const REFRESH_KEY = 'shang_admin_refresh'

export interface AuthUser {
  id?: number | string
  displayName: string
  username: string
  roles?: string[]
  permissions?: string[]
  menus?: MenuNode[]
}

function loadUser(): AuthUser | null {
  try {
    const raw = localStorage.getItem(USER_KEY)
    return raw ? (JSON.parse(raw) as AuthUser) : null
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(getToken())
  const user = ref<AuthUser | null>(loadUser())

  const isAuthenticated = computed(() => Boolean(token.value))
  const permissions = computed(() => user.value?.permissions ?? [])
  const menus = computed(() => user.value?.menus ?? [])
  const roles = computed(() => user.value?.roles ?? [])

  function persistUser(next: AuthUser) {
    user.value = next
    localStorage.setItem(USER_KEY, JSON.stringify(next))
  }

  function normalizeUser(src: {
    id?: number | string
    username: string
    realName?: string
    roles?: string[]
    permissions?: string[] | Set<string>
    menus?: MenuNode[]
  }): AuthUser {
    const perms = src.permissions
      ? Array.isArray(src.permissions)
        ? [...src.permissions]
        : [...src.permissions]
      : []
    return {
      id: src.id,
      username: src.username,
      displayName: src.realName || src.username || '管理员',
      roles: src.roles ? [...src.roles] : [],
      permissions: perms,
      menus: src.menus,
    }
  }

  async function login(payload: LoginPayload) {
    const res = await loginApi(payload)
    const data = res.data
    const accessToken = data?.accessToken
    if (!accessToken || !data.user) {
      throw new Error(res.msg || '登录失败')
    }
    token.value = accessToken
    setToken(accessToken)
    if (data.refreshToken) {
      localStorage.setItem(REFRESH_KEY, data.refreshToken)
    }
    persistUser(normalizeUser(data.user))
    return res
  }

  async function loadProfile() {
    if (!token.value) return
    try {
      const res = await fetchMe()
      const me = res.data
      if (!me) return
      persistUser(normalizeUser(me))
    } catch {
      /* ignore */
    }
  }

  async function logout() {
    try {
      if (token.value) await logoutApi()
    } catch {
      // ignore network errors on logout
    } finally {
      token.value = null
      user.value = null
      clearToken()
      localStorage.removeItem(USER_KEY)
      localStorage.removeItem(REFRESH_KEY)
      useTabsStore().reset()
    }
  }

  function hydrate() {
    token.value = getToken()
    user.value = loadUser()
  }

  return {
    token,
    user,
    permissions,
    menus,
    roles,
    isAuthenticated,
    login,
    logout,
    hydrate,
    loadProfile,
  }
})
