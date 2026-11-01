import { computed, ref } from 'vue'

/** 后台列表统一分页大小（与商品列表一致） */
export const LIST_PAGE_SIZE = 10

export function usePager(pageSize = LIST_PAGE_SIZE) {
  const page = ref(1)
  const total = ref(0)

  const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))
  const canPrev = computed(() => page.value > 1)
  const canNext = computed(() => page.value < totalPages.value)

  function resetPage() {
    page.value = 1
  }

  /** 当前页无数据但 total>0 时回退到末页；返回是否已回退（调用方应重新 load） */
  function clampEmptyPage(recordCount: number): boolean {
    if (page.value > 1 && recordCount === 0 && total.value > 0) {
      page.value = Math.max(1, Math.ceil(total.value / pageSize))
      return true
    }
    return false
  }

  async function goPrev(load: () => Promise<void>) {
    if (!canPrev.value) return
    page.value -= 1
    await load()
  }

  async function goNext(load: () => Promise<void>) {
    if (!canNext.value) return
    page.value += 1
    await load()
  }

  return {
    page,
    total,
    pageSize,
    totalPages,
    canPrev,
    canNext,
    resetPage,
    clampEmptyPage,
    goPrev,
    goNext,
  }
}
