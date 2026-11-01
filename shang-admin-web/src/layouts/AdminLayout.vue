<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppSidebar from '@/components/AppSidebar.vue'
import AppTabs from '@/components/AppTabs.vue'
import { CRUMB_MAP, resolveNavKey } from '@/config/nav'
import { useAuthStore } from '@/stores/auth'
import { useTabsStore } from '@/stores/tabs'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const tabs = useTabsStore()

const menuOpen = ref(false)

const crumb = computed(() => {
  const key = resolveNavKey(route.path)
  return CRUMB_MAP[key] || { group: '', title: String(route.meta.title || '') }
})

const displayName = computed(() => auth.user?.displayName || '管理员')
const username = computed(() => auth.user?.username || '—')
const avatarChar = computed(() => displayName.value.slice(0, 1))

function toggleMenu() {
  menuOpen.value = !menuOpen.value
}

function closeMenu() {
  menuOpen.value = false
}

async function handleLogout() {
  closeMenu()
  await auth.logout()
  router.push({ path: '/login' })
}

function onDocClick(e: MouseEvent) {
  const target = e.target as HTMLElement | null
  if (!target?.closest('.user-menu')) closeMenu()
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') closeMenu()
}

watch(
  () => route.fullPath,
  () => {
    tabs.openFromRoute(route)
    closeMenu()
  },
  { immediate: true },
)

onMounted(() => {
  document.addEventListener('click', onDocClick)
  document.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick)
  document.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div class="layout">
    <AppSidebar />
    <main class="main">
      <header class="topbar">
        <div class="crumbs">
          {{ crumb.group }} / <b>{{ crumb.title }}</b>
        </div>
        <div class="topbar-right">
          <span v-if="route.path === '/dashboard'" class="badge badge-forest">在线</span>
          <div class="user-menu">
            <button
              class="user-trigger"
              type="button"
              :aria-expanded="menuOpen"
              aria-haspopup="menu"
              @click.stop="toggleMenu"
            >
              <div class="avatar">{{ avatarChar }}</div>
              <span class="user-name">{{ displayName }}</span>
              <svg class="user-caret" viewBox="0 0 12 12" aria-hidden="true">
                <path
                  d="M2.5 4.5L6 8L9.5 4.5"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="1.5"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
              </svg>
            </button>
            <div v-if="menuOpen" class="user-dropdown" role="menu">
              <div class="user-dropdown-meta">
                <div class="name">{{ displayName }}</div>
                <div class="account">{{ username }}</div>
              </div>
              <button
                class="user-dropdown-item danger"
                type="button"
                role="menuitem"
                @click="handleLogout"
              >
                退出系统
              </button>
            </div>
          </div>
        </div>
      </header>
      <AppTabs />
      <div class="content">
        <RouterView v-slot="{ Component }">
          <KeepAlive :max="16">
            <component :is="Component" :key="route.path" />
          </KeepAlive>
        </RouterView>
      </div>
    </main>
  </div>
</template>
