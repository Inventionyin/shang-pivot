<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import NavIcon from '@/components/NavIcon.vue'
import { useTabsStore, type AppTab } from '@/stores/tabs'

const router = useRouter()
const tabsStore = useTabsStore()

const menu = ref({ show: false, x: 0, y: 0, path: '' })

function go(path: string) {
  tabsStore.activate(path)
  if (router.currentRoute.value.path !== path) {
    void router.push(path)
  }
}

function onClose(e: Event, path: string) {
  e.stopPropagation()
  const next = tabsStore.close(path)
  if (next && router.currentRoute.value.path !== next) {
    void router.push(next)
  }
}

function onContext(e: MouseEvent, tab: AppTab) {
  e.preventDefault()
  menu.value = { show: true, x: e.clientX, y: e.clientY, path: tab.path }
}

function hideMenu() {
  menu.value.show = false
}

function ctxClose() {
  const path = menu.value.path
  const tab = tabsStore.tabs.find((t) => t.path === path)
  hideMenu()
  if (!tab?.closable) return
  const next = tabsStore.close(path)
  if (next && router.currentRoute.value.path !== next) {
    void router.push(next)
  }
}

function ctxCloseOthers() {
  const path = menu.value.path
  hideMenu()
  tabsStore.closeOthers(path)
  if (router.currentRoute.value.path !== path) {
    void router.push(path)
  }
}

function ctxCloseAll() {
  hideMenu()
  const home = tabsStore.closeAll()
  if (router.currentRoute.value.path !== home) {
    void router.push(home)
  }
}

function onWheel(e: WheelEvent) {
  const el = e.currentTarget as HTMLElement
  if (el.scrollWidth <= el.clientWidth) return
  if (Math.abs(e.deltaY) > Math.abs(e.deltaX)) {
    el.scrollLeft += e.deltaY
  }
}
</script>

<template>
  <div class="app-tabs-bar" @click="hideMenu">
    <div class="app-tabs" @wheel="onWheel">
      <button
        v-for="tab in tabsStore.tabs"
        :key="tab.path"
        type="button"
        class="app-tab"
        :class="{ active: tabsStore.activePath === tab.path }"
        @click="go(tab.path)"
        @contextmenu="onContext($event, tab)"
      >
        <NavIcon :name="tab.icon" />
        <span class="app-tab-title">{{ tab.title }}</span>
        <span
          v-if="tab.closable"
          class="app-tab-close"
          title="关闭"
          @click="onClose($event, tab.path)"
        >×</span>
      </button>
    </div>

    <Teleport to="body">
      <div
        v-if="menu.show"
        class="app-tabs-ctx-mask"
        @click="hideMenu"
        @contextmenu.prevent="hideMenu"
      />
      <ul
        v-if="menu.show"
        class="app-tabs-ctx"
        :style="{ left: menu.x + 'px', top: menu.y + 'px' }"
      >
        <li
          :class="{ disabled: !tabsStore.tabs.find((t) => t.path === menu.path)?.closable }"
          @click="ctxClose"
        >
          关闭
        </li>
        <li @click="ctxCloseOthers">关闭其他</li>
        <li @click="ctxCloseAll">关闭全部</li>
      </ul>
    </Teleport>
  </div>
</template>
