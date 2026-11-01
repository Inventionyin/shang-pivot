import { computed, onMounted, onUnmounted, ref } from 'vue'

export type ClientType = 'web' | 'h5'

const width = ref(typeof window !== 'undefined' ? window.innerWidth : 390)

function onResize() {
  width.value = window.innerWidth
}

/** Web ≥768；否则按 H5（含窄窗调试） */
export function useClientType() {
  onMounted(() => {
    width.value = window.innerWidth
    window.addEventListener('resize', onResize)
  })
  onUnmounted(() => window.removeEventListener('resize', onResize))

  const clientType = computed<ClientType>(() => (width.value >= 768 ? 'web' : 'h5'))
  const isH5 = computed(() => clientType.value === 'h5')
  const isWeb = computed(() => clientType.value === 'web')

  return { clientType, isH5, isWeb, width }
}

export function detectClientType(): ClientType {
  if (typeof window === 'undefined') return 'h5'
  return window.innerWidth >= 768 ? 'web' : 'h5'
}
