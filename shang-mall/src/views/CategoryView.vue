<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ProductCard from '@/components/ProductCard.vue'
import ProductPager from '@/components/ProductPager.vue'
import StoreIcon from '@/components/StoreIcon.vue'
import type { IconName } from '@/components/storeIcons'
import {
  fetchCategories,
  fetchProducts,
  type CategoryNode,
  type ProductCard as ProductCardType,
} from '@/api/catalog'
import { useClientType } from '@/composables/useClientType'

const PAGE_SIZE = 20

/** 排序（PRD §8.4 / 原型 category.html chips）：综合 / 销量 / 价格升 / 价格降 */
type SortKey = 'comp' | 'sold' | 'price_asc' | 'price_desc'

const SORT_OPTIONS: Array<{ key: SortKey; label: string; icon?: IconName; aria: string }> = [
  { key: 'comp', label: '综合', aria: '按综合排序' },
  { key: 'sold', label: '销量', aria: '按销量排序' },
  { key: 'price_asc', label: '价格', icon: 'arrow-up', aria: '按价格从低到高排序' },
  { key: 'price_desc', label: '价格', icon: 'arrow-down', aria: '按价格从高到低排序' },
]

const route = useRoute()
const router = useRouter()
const { isH5, isWeb } = useClientType()

const categories = ref<CategoryNode[]>([])
const activeId = ref('')
const sort = ref<SortKey>('comp')
const products = ref<ProductCardType[]>([])
const loading = ref(true)
const page = ref(1)
const total = ref(0)
const listLoading = ref(false)
const listFinished = ref(false)

function flatten(nodes: CategoryNode[]): CategoryNode[] {
  const out: CategoryNode[] = []
  for (const n of nodes) {
    if (n.children?.length) out.push(...flatten(n.children))
    else out.push(n)
  }
  return out
}

const flatCategories = computed(() => flatten(categories.value))
const activeName = computed(
  () => flatCategories.value.find((c) => String(c.id) === String(activeId.value))?.name || '商品',
)

async function loadProducts(reset = true) {
  if (!activeId.value) {
    products.value = []
    total.value = 0
    listFinished.value = true
    loading.value = false
    return
  }
  if (reset) {
    page.value = 1
    loading.value = true
    listFinished.value = false
  }
  try {
    const res = await fetchProducts({
      categoryId: String(activeId.value),
      page: page.value,
      size: PAGE_SIZE,
      sort: sort.value,
    })
    const records = res.data.records || []
    total.value = Number(res.data.total || 0)
    // 价格排序已由后端 SQL 完成（CatalogReadService.applySort 按 SKU 最低价），此处不再二次排序
    products.value = reset ? records : [...products.value, ...records]
    listFinished.value = products.value.length >= total.value || records.length === 0
  } catch {
    if (reset) {
      products.value = []
      total.value = 0
    }
    listFinished.value = true
  } finally {
    loading.value = false
  }
}

function selectSort(key: SortKey) {
  if (key === sort.value) return
  sort.value = key
  void loadProducts(true).then(() => window.scrollTo({ top: 0, behavior: 'smooth' }))
}

function onPageChange(p: number) {
  if (p === page.value) return
  page.value = p
  void loadProducts(true).then(() => window.scrollTo({ top: 0, behavior: 'smooth' }))
}

async function onLoadMore() {
  if (listFinished.value || loading.value) {
    listLoading.value = false
    return
  }
  listLoading.value = true
  page.value += 1
  try {
    await loadProducts(false)
  } finally {
    listLoading.value = false
  }
}

function selectCategory(id: string) {
  if (id === activeId.value) return
  activeId.value = id
}

onMounted(async () => {
  try {
    const res = await fetchCategories()
    categories.value = res.data || []
    const qid = String(route.query.categoryId || '')
    const first = categories.value[0]
    activeId.value =
      qid || String(first?.children?.[0]?.id || first?.id || '')
  } catch {
    categories.value = []
  }
  await loadProducts(true)
})

watch(activeId, () => {
  void loadProducts(true)
})

watch(
  () => route.query.categoryId,
  (qid) => {
    const next = String(qid || '')
    if (next && next !== activeId.value) activeId.value = next
  },
)
</script>

<template>
  <div class="page category-page" :class="{ 'category-page--h5': isH5 }">
    <div v-if="isH5" class="h5-cat-search" @click="router.push('/search')">
      <span>搜索分类商品</span>
    </div>
    <h1 v-else class="page-title">分类</h1>

    <div class="category-layout">
      <aside class="side">
        <button
          v-for="c in flatCategories"
          :key="String(c.id)"
          class="side-item"
          :class="{ active: String(c.id) === String(activeId) }"
          type="button"
          @click="selectCategory(String(c.id))"
        >
          {{ c.name }}
        </button>
        <p v-if="!categories.length" class="side-empty">暂无类目</p>
      </aside>

      <section class="main">
        <!-- 排序 chips（PRD §8.4 / 原型 category.html）：Web 与 H5 同一套，H5 紧凑布局 -->
        <div class="sort-bar" :class="{ 'sort-bar--h5': isH5 }">
          <div v-if="isH5" class="main-title">{{ activeName }}</div>
          <h2 v-else class="main-name">{{ activeName }}</h2>
          <div class="sort-chips" role="group" aria-label="排序方式">
            <button
              v-for="o in SORT_OPTIONS"
              :key="o.key"
              type="button"
              class="chip"
              :class="{ active: sort === o.key }"
              :aria-pressed="sort === o.key"
              :aria-label="o.aria"
              @click="selectSort(o.key)"
            >
              <span>{{ o.label }}</span>
              <StoreIcon v-if="o.icon" :name="o.icon" :size="12" />
            </button>
          </div>
        </div>
        <van-skeleton v-if="loading && !products.length" title :row="4" />

        <!-- Web：页码分页 -->
        <template v-else-if="isWeb">
          <div v-if="products.length" class="card-grid">
            <ProductCard v-for="p in products" :key="String(p.spuId)" :item="p" />
          </div>
          <ProductPager
            v-if="products.length"
            :page="page"
            :total="total"
            :page-size="PAGE_SIZE"
            @update:page="onPageChange"
          />
          <div v-else class="empty-hint">
            <strong>该类目暂无商品</strong>
            换一个类目看看
          </div>
        </template>

        <!-- H5：触底加载 -->
        <van-list
          v-else
          v-model:loading="listLoading"
          :finished="listFinished"
          finished-text="没有更多了"
          @load="onLoadMore"
        >
          <div v-if="products.length" class="card-grid">
            <ProductCard v-for="p in products" :key="String(p.spuId)" :item="p" />
          </div>
          <div v-else-if="!loading" class="empty-hint">
            <strong>该类目暂无商品</strong>
            换一个类目看看
          </div>
        </van-list>
      </section>
    </div>
  </div>
</template>

<style scoped>
.category-layout {
  display: grid;
  grid-template-columns: 96px 1fr;
  gap: 12px;
  min-height: 60vh;
}

@media (min-width: 768px) {
  .category-layout {
    grid-template-columns: 180px 1fr;
    gap: 20px;
  }
}

.category-page--h5 {
  padding-top: 0;
}

.h5-cat-search {
  margin: 0 0 12px;
  padding: 10px 14px;
  background: var(--sand);
  border: 1px solid var(--border);
  border-radius: 999px;
  color: var(--muted);
  font-size: 13px;
}

.main-title {
  font-size: 14px;
  font-weight: 700;
  margin-bottom: 10px;
}

.main-name {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
}

/* 排序工具条 + chips（对齐原型 .web-toolbar / .chip.active） */
.sort-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.sort-bar--h5 {
  display: block;
}

.sort-chips {
  display: flex;
  align-items: center;
  gap: 8px;
  overflow-x: auto;
  scrollbar-width: none;
}

.sort-chips::-webkit-scrollbar {
  display: none;
}

/* H5：紧凑 chips，避免占满首屏 */
.sort-bar--h5 .chip {
  padding: 5px 10px;
  font-size: 12px;
  border-radius: var(--r-sm);
}

.sort-bar--h5 .sort-chips {
  margin-bottom: 10px;
}

.side {
  background: var(--sand);
  border: 1px solid var(--border);
  border-radius: 14px;
  padding: 8px;
  align-self: start;
  max-height: calc(100vh - 160px);
  overflow-y: auto;
  position: sticky;
  top: 12px;
}

.side-item {
  width: 100%;
  border: 0;
  background: transparent;
  text-align: left;
  padding: 10px 8px;
  border-radius: 10px;
  color: var(--ink);
  cursor: pointer;
  font-size: 13px;
}

.side-item.active {
  background: var(--amber);
  font-weight: 600;
}

.side-empty {
  margin: 8px;
  font-size: 12px;
  color: var(--muted);
}
</style>
