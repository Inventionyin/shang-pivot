<script setup lang="ts">
import { computed } from 'vue'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

const props = defineProps<{ name?: string | null }>()

/** 旧版自研 key → Element Plus 图标名 */
const ALIAS: Record<string, string> = {
  dashboard: 'Odometer',
  products: 'Goods',
  'product-edit': 'Edit',
  categories: 'Menu',
  inventory: 'Box',
  orders: 'Tickets',
  aftersales: 'RefreshLeft',
  users: 'User',
  roles: 'UserFilled',
  menus: 'Grid',
  depts: 'OfficeBuilding',
  dicts: 'Collection',
  logs: 'Document',
  settings: 'Tools',
}

const iconMap = ElementPlusIconsVue as Record<string, unknown>

const resolved = computed(() => {
  const raw = (props.name || '').trim()
  if (!raw) return 'Setting'
  if (iconMap[raw]) return raw
  const aliased = ALIAS[raw]
  if (aliased && iconMap[aliased]) return aliased
  return 'Setting'
})

const comp = computed(() => iconMap[resolved.value] as object)
</script>

<template>
  <component :is="comp" class="ico nav-icon-el" />
</template>

<style scoped>
.nav-icon-el {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
}
</style>
