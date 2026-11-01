import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { resolvePageMeta } from '@/config/nav'

export interface AppTab {
  /** 路由 path，作为唯一键 */
  path: string
  title: string
  icon: string
  closable: boolean
}

const HOME: AppTab = {
  path: '/dashboard',
  title: '工作台',
  icon: 'dashboard',
  closable: false,
}

export const useTabsStore = defineStore('tabs', () => {
  const tabs = ref<AppTab[]>([{ ...HOME }])
  const activePath = ref(HOME.path)

  function openFromRoute(route: RouteLocationNormalizedLoaded) {
    if (route.path === '/login' || route.meta.public) return

    const path = route.path
    const meta = resolvePageMeta(path, route.meta.title as string | undefined)
    const closable = path !== HOME.path

    const exists = tabs.value.find((t) => t.path === path)
    if (exists) {
      exists.title = meta.title
      exists.icon = meta.icon
    } else {
      tabs.value.push({ path, title: meta.title, icon: meta.icon, closable })
    }
    activePath.value = path
  }

  function activate(path: string) {
    activePath.value = path
  }

  /** 关闭后返回应跳转的 path；无需跳转则 null */
  function close(path: string): string | null {
    const idx = tabs.value.findIndex((t) => t.path === path)
    if (idx < 0) return null
    const tab = tabs.value[idx]!
    if (!tab.closable) return null

    const wasActive = activePath.value === path
    tabs.value.splice(idx, 1)

    if (!wasActive) return null
    const next = tabs.value[idx] || tabs.value[idx - 1] || HOME
    activePath.value = next.path
    return next.path
  }

  function closeOthers(path: string) {
    tabs.value = tabs.value.filter((t) => !t.closable || t.path === path)
    activePath.value = path
  }

  function closeAll(): string {
    tabs.value = [{ ...HOME }]
    activePath.value = HOME.path
    return HOME.path
  }

  function reset() {
    tabs.value = [{ ...HOME }]
    activePath.value = HOME.path
  }

  return { tabs, activePath, openFromRoute, activate, close, closeOthers, closeAll, reset }
})
