<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import NavIcon from '@/components/NavIcon.vue'
import { resolveNavKey } from '@/config/nav'
import { useAuthStore } from '@/stores/auth'
import { menusToSidebarGroups } from '@/utils/menu'
import { hasPermission } from '@/utils/permission'

const route = useRoute()
const auth = useAuthStore()

const activeKey = computed(() => resolveNavKey(route.path))

const groups = computed(() => {
  const raw = menusToSidebarGroups(auth.user?.menus)
  return raw
    .map((g) => ({
      ...g,
      items: g.items.filter((item) => !item.code || hasPermission(item.code)),
    }))
    .filter((g) => g.items.length > 0)
})
</script>

<template>
  <aside class="sidebar">
    <div class="brand">
      <img class="brand-logo" src="/logo.png" alt="Shang" />
      <div class="brand-name">Shang 后台</div>
    </div>
    <nav class="nav">
      <template v-for="group in groups" :key="group.title + group.items.map((i) => i.key).join(',')">
        <div v-if="group.title" class="nav-group-title">{{ group.title }}</div>
        <RouterLink
          v-for="item in group.items"
          :key="item.key"
          class="nav-item"
          :class="{ active: activeKey === item.key || route.path === item.path }"
          :to="item.path"
        >
          <NavIcon :name="item.icon" />
          <span>{{ item.label }}</span>
        </RouterLink>
      </template>
    </nav>
  </aside>
</template>
