<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ProductCard from '@/components/ProductCard.vue'
import ProductPager from '@/components/ProductPager.vue'
import {
  fetchHome,
  fetchProducts,
  searchProducts,
  type ProductCard as ProductCardType,
} from '@/api/catalog'
import { useClientType } from '@/composables/useClientType'

const PAGE_SIZE = 20
const HISTORY_KEY = 'shang_mall_search_history'

const route = useRoute()
const router = useRouter()
const { isH5, isWeb } = useClientType()

const q = ref('')
const loading = ref(false)
const records = ref<ProductCardType[]>([])
const searched = ref(false)
const page = ref(1)
const total = ref(0)
const listLoading = ref(false)
const listFinished = ref(false)
const hotKeywords = ref<string[]>([])
const history = ref<string[]>([])
const recommend = ref<ProductCardType[]>([])

const showLanding = computed(() => !searched.value && !q.value.trim())

function loadHistory() {
  try {
    const raw = localStorage.getItem(HISTORY_KEY)
    const arr = raw ? (JSON.parse(raw) as string[]) : []
    history.value = Array.isArray(arr) ? arr.slice(0, 10) : []
  } catch {
    history.value = []
  }
}

function saveHistory(keyword: string) {
  const next = [keyword, ...history.value.filter((h) => h !== keyword)].slice(0, 10)
  history.value = next
  localStorage.setItem(HISTORY_KEY, JSON.stringify(next))
}

function clearHistory() {
  history.value = []
  localStorage.removeItem(HISTORY_KEY)
}

async function loadPage(reset = true) {
  const keyword = q.value.trim()
  searched.value = true
  if (!keyword) {
    records.value = []
    total.value = 0
    listFinished.value = true
    return
  }
  if (reset) {
    page.value = 1
    loading.value = true
    listFinished.value = false
  }
  try {
    const res = await searchProducts({ q: keyword, page: page.value, size: PAGE_SIZE })
    const list = res.data.records || []
    total.value = Number(res.data.total || 0)
    records.value = reset ? list : [...records.value, ...list]
    listFinished.value = records.value.length >= total.value || list.length === 0
    if (reset) saveHistory(keyword)
  } catch {
    if (reset) {
      records.value = []
      total.value = 0
    }
    listFinished.value = true
  } finally {
    loading.value = false
  }
}

function onSearch() {
  const keyword = q.value.trim()
  if (!keyword) {
    searched.value = false
    records.value = []
    return
  }
  void router.replace({ path: '/search', query: { q: keyword } })
  void loadPage(true)
}

function onPageChange(p: number) {
  if (p === page.value) return
  page.value = p
  void loadPage(true).then(() => window.scrollTo({ top: 0, behavior: 'smooth' }))
}

async function onLoadMore() {
  if (listFinished.value || loading.value || !searched.value) {
    listLoading.value = false
    return
  }
  listLoading.value = true
  page.value += 1
  try {
    await loadPage(false)
  } finally {
    listLoading.value = false
  }
}

function applyKeyword(kw: string) {
  q.value = kw
  onSearch()
}

onMounted(async () => {
  loadHistory()
  try {
    const [homeRes, feedRes] = await Promise.all([
      fetchHome(8),
      fetchProducts({ page: 1, size: 6, sort: 'sold' }),
    ])
    const slot = (homeRes.data.slots || []).find((s) => s.code === 'home_hot_keywords')
    hotKeywords.value = (slot?.items || []).map((it) => it.title).filter(Boolean) as string[]
    recommend.value = feedRes.data.records || []
  } catch {
    /* ignore */
  }
  const init = String(route.query.q || '')
  if (init) {
    q.value = init
    void loadPage(true)
  }
})

watch(
  () => route.query.q,
  (v) => {
    const next = String(v || '')
    if (next && next !== q.value) {
      q.value = next
      void loadPage(true)
    }
  },
)
</script>

<template>
  <div class="page search-page">
    <van-search
      v-model="q"
      show-action
      placeholder="搜索商品"
      @search="onSearch"
      @cancel="$router.back()"
    >
      <template #action>
        <div @click="onSearch">搜索</div>
      </template>
    </van-search>

    <!-- 搜索落地：热搜 + 历史 + 无结果推荐 -->
    <div v-if="showLanding" class="landing">
      <div v-if="history.length" class="block">
        <div class="block-head">
          <strong>历史搜索</strong>
          <button type="button" @click="clearHistory">清空</button>
        </div>
        <div class="tags">
          <button v-for="h in history" :key="h" type="button" @click="applyKeyword(h)">
            {{ h }}
          </button>
        </div>
      </div>
      <div v-if="hotKeywords.length" class="block">
        <div class="block-head"><strong>热门搜索</strong></div>
        <div class="tags">
          <button
            v-for="(kw, i) in hotKeywords"
            :key="kw"
            type="button"
            :class="{ hot: i === 0 }"
            @click="applyKeyword(kw)"
          >
            {{ kw }}
          </button>
        </div>
      </div>
    </div>

    <van-skeleton v-else-if="loading && !records.length" title :row="4" />

    <template v-else-if="records.length">
      <div v-if="isWeb" class="card-grid">
        <ProductCard v-for="p in records" :key="p.spuId" :item="p" />
      </div>
      <ProductPager
        v-if="isWeb"
        :page="page"
        :total="total"
        :page-size="PAGE_SIZE"
        @update:page="onPageChange"
      />

      <van-list
        v-if="isH5"
        v-model:loading="listLoading"
        :finished="listFinished"
        finished-text="没有更多了"
        @load="onLoadMore"
      >
        <div class="card-grid">
          <ProductCard v-for="p in records" :key="p.spuId" :item="p" />
        </div>
      </van-list>
    </template>

    <div v-else-if="searched" class="empty-hint">
      <strong>没有找到相关商品</strong>
      换个关键词试试
      <div v-if="recommend.length" class="reco">
        <div class="reco-title">为你推荐</div>
        <div class="card-grid">
          <ProductCard v-for="p in recommend" :key="p.spuId" :item="p" />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.landing {
  padding: 8px 4px 24px;
}
.block {
  margin-bottom: 20px;
}
.block-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
  font-size: 14px;
}
.block-head button {
  border: 0;
  background: transparent;
  color: var(--muted);
  font-size: 12px;
  cursor: pointer;
}
.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.tags button {
  border: 1px solid var(--border);
  background: var(--sand);
  border-radius: 999px;
  padding: 6px 12px;
  font-size: 12px;
  color: var(--ink);
  cursor: pointer;
}
.tags button.hot {
  color: var(--coral);
  font-weight: 600;
  border-color: rgba(255, 138, 91, 0.4);
}
.reco {
  margin-top: 24px;
  text-align: left;
}
.reco-title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 12px;
  color: var(--ink);
}
</style>
