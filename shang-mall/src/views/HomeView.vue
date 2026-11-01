<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import ProductCard from '@/components/ProductCard.vue'
import ProductPager from '@/components/ProductPager.vue'
import {
  fetchCategories,
  fetchHome,
  fetchProducts,
  type CategoryNode,
  type HomeSlot,
  type HomeSlotItem,
  type ProductCard as ProductCardType,
} from '@/api/catalog'
import { useBrandStore } from '@/stores/brand'
import { useAuthStore } from '@/stores/auth'
import { useCartStore } from '@/stores/cart'
import { useClientType } from '@/composables/useClientType'
import { openLoginLayer } from '@/composables/useLoginLayer'
import { resolveMediaUrl } from '@/utils/media'
import { formatPrice } from '@/utils/format'
import { navigateSlotLink } from '@/utils/nav'
import StoreIcon from '@/components/StoreIcon.vue'

const PAGE_SIZE = 20

const brand = useBrandStore()
const auth = useAuthStore()
const cart = useCartStore()
const router = useRouter()
const { isH5, isWeb } = useClientType()

const loading = ref(true)
const feedLoading = ref(false)
const listLoading = ref(false)
const listFinished = ref(false)
const refreshing = ref(false)
const feeds = ref<ProductCardType[]>([])
const feedPage = ref(1)
const feedTotal = ref(0)
const slots = ref<HomeSlot[]>([])
const categories = ref<CategoryNode[]>([])
const errorMsg = ref('')

const banners = computed(() => {
  const slot = slots.value.find((s) => s.code === 'home_banner')
  return (slot?.items || []).map((it, i) => ({
    id: String(i),
    picUrl: resolveMediaUrl(it.imageUrl || it.imageKey || it.pic),
    title: it.title || '',
    linkType: it.linkType,
    linkValue: it.linkValue,
    spuId: it.spuId,
  }))
})

const hotKeywords = computed(() => {
  const slot = slots.value.find((s) => s.code === 'home_hot_keywords')
  return (slot?.items || []).map((it) => it.title).filter(Boolean) as string[]
})

/** 运营位槽位：V2 口径为「活动位」，标题一律取后台配置 */
const activityHot = computed(() => slots.value.find((s) => s.code === 'home_promo_subsidy'))
const activityNew = computed(() => slots.value.find((s) => s.code === 'home_promo_flash'))
const activityBrand = computed(() => slots.value.find((s) => s.code === 'home_promo_live'))
const hotSpus = computed(() => slots.value.find((s) => s.code === 'home_hot_spus'))

const searchPlaceholder = computed(
  () => hotKeywords.value.slice(0, 2).join(' ') || '搜索商品',
)

const catLinks = computed(() =>
  categories.value.slice(0, 8).map((c) => ({
    id: String(c.children?.[0]?.id || c.id),
    name: c.name,
  })),
)

const cartPreview = computed(() => cart.items.filter((i) => i.available).slice(0, 2))

const activeBanner = computed(() => banners.value[0])

const activityHotItem = computed(() => firstSpuItem(activityHot.value))
const activityNewItem = computed(() => firstSpuItem(activityNew.value))
const activityBrandItem = computed(() => firstSpuItem(activityBrand.value))

function firstSpuItem(slot?: HomeSlot): HomeSlotItem | undefined {
  return (slot?.items || []).find((i) => i.spuId)
}

async function loadFeed(page = 1, append = false) {
  feedLoading.value = true
  try {
    const res = await fetchProducts({ page, size: PAGE_SIZE, sort: 'sold' })
    const records = res.data.records || []
    feedTotal.value = Number(res.data.total || 0)
    feedPage.value = page
    feeds.value = append ? [...feeds.value, ...records] : records
    listFinished.value = feeds.value.length >= feedTotal.value || records.length === 0
  } catch {
    if (!append) {
      feeds.value = []
      feedTotal.value = 0
    }
    listFinished.value = true
  } finally {
    feedLoading.value = false
  }
}

async function loadHomeMeta() {
  const [homeRes, catRes] = await Promise.all([fetchHome(PAGE_SIZE), fetchCategories()])
  slots.value = homeRes.data.slots || []
  categories.value = catRes.data || []
  if (!feeds.value.length && homeRes.data.feed?.length) {
    feeds.value = homeRes.data.feed
    feedTotal.value = homeRes.data.feed.length
    listFinished.value = feeds.value.length >= feedTotal.value
  }
}

function onFeedPageChange(p: number) {
  if (p === feedPage.value) return
  void loadFeed(p, false)
  document.querySelector('.web-feed')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

async function onLoadMore() {
  if (listFinished.value || feedLoading.value) {
    listLoading.value = false
    return
  }
  listLoading.value = true
  try {
    await loadFeed(feedPage.value + 1, true)
  } finally {
    listLoading.value = false
  }
}

async function onRefresh() {
  refreshing.value = true
  try {
    await Promise.all([loadHomeMeta(), loadFeed(1, false)])
    if (auth.isLoggedIn) await cart.refresh()
  } finally {
    refreshing.value = false
  }
}

onMounted(async () => {
  try {
    await Promise.all([loadHomeMeta(), loadFeed(1, false)])
    if (auth.isLoggedIn) await cart.refresh()
  } catch {
    errorMsg.value = '首页加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
})

function onBannerClick(b?: {
  linkType?: string
  linkValue?: string | null
  spuId?: string | number
}) {
  navigateSlotLink(router, b)
}

function slotItems(slot?: HomeSlot, n = 2) {
  return (slot?.items || []).filter((i) => i.spuId).slice(0, n)
}

function goPromo(code: string) {
  void router.push(`/promo/${code}`)
}

function goSearch(q?: string) {
  void router.push(q ? { path: '/search', query: { q } } : '/search')
}

/** PRD §8.0：未登录点需登录的入口拉起登录层，成功后回原场景 */
async function onLogin() {
  await openLoginLayer()
}
</script>

<template>
  <div class="home" :class="{ 'home--web': isWeb, 'home--h5': isH5 }">
    <van-skeleton v-if="loading" title :row="8" />

    <div v-else-if="errorMsg" class="empty-hint">
      <strong>{{ errorMsg }}</strong>
    </div>

    <!-- ========== Web 门户首页 ========== -->
    <template v-else-if="isWeb">
      <div class="web-portal-grid">
        <aside class="web-cat-panel">
          <div class="head">商品分类</div>
          <RouterLink
            v-for="c in catLinks"
            :key="c.id"
            :to="{ path: '/category', query: { categoryId: c.id } }"
          >
            {{ c.name }}
          </RouterLink>
          <RouterLink v-if="!catLinks.length" to="/category">全部类目</RouterLink>
        </aside>

        <section class="web-promo-center">
          <div class="web-promo-hero">
            <div class="web-promo-banner" @click="onBannerClick(activeBanner)">
              <img
                v-if="activeBanner?.picUrl"
                :src="activeBanner.picUrl"
                :alt="activeBanner.title"
              />
              <div class="cap">
                <h3>{{ activeBanner?.title || brand.displayName }}</h3>
                <p>{{ brand.slogan }} · 满 99 元免运费</p>
              </div>
            </div>
            <div class="web-promo-tiles">
              <div
                class="web-promo-tile"
                role="link"
                tabindex="0"
                @click="goPromo('home_promo_subsidy')"
                @keydown.enter="goPromo('home_promo_subsidy')"
              >
                <div>
                  <div class="label">{{ activityHot?.title || '活动位 · 热门精选' }}</div>
                  <div class="sub">按销量排序的上架商品</div>
                </div>
                <div class="items" @click.stop>
                  <RouterLink
                    v-for="it in slotItems(activityHot, 2)"
                    :key="String(it.spuId)"
                    :to="`/product/${it.spuId}`"
                  >
                    <img :src="resolveMediaUrl(it.pic)" :alt="it.name" />
                    <div class="price-tag">¥{{ formatPrice(it.price) }}</div>
                  </RouterLink>
                </div>
              </div>
              <div
                class="web-promo-tile"
                role="link"
                tabindex="0"
                @click="goPromo('home_hot_spus')"
                @keydown.enter="goPromo('home_hot_spus')"
              >
                <div>
                  <div class="label">{{ hotSpus?.title || '热门精选' }}</div>
                  <div class="sub">精选上架好物</div>
                </div>
                <div class="items" @click.stop>
                  <RouterLink
                    v-for="it in slotItems(hotSpus, 2)"
                    :key="String(it.spuId)"
                    :to="`/product/${it.spuId}`"
                  >
                    <img :src="resolveMediaUrl(it.pic)" :alt="it.name" />
                    <div class="price-tag">¥{{ formatPrice(it.price) }}</div>
                  </RouterLink>
                </div>
              </div>
            </div>
          </div>

          <div class="web-promo-row2">
            <div
              class="web-mini-card"
              role="link"
              tabindex="0"
              @click="goPromo('home_promo_live')"
              @keydown.enter="goPromo('home_promo_live')"
            >
              <div class="title" style="color: var(--coral)">
                {{ activityBrand?.title || '活动位 · 品牌专区' }}
              </div>
              <img
                v-if="activityBrandItem"
                :src="resolveMediaUrl(activityBrandItem.pic)"
                :alt="activityBrandItem.name"
              />
              <div class="hint">{{ activityBrandItem?.name || '品牌好物' }}</div>
            </div>
            <div
              class="web-mini-card"
              role="link"
              tabindex="0"
              @click="goPromo('home_promo_flash')"
              @keydown.enter="goPromo('home_promo_flash')"
            >
              <div class="title">{{ activityNew?.title || '活动位 · 新品上架' }}</div>
              <div v-if="activityNewItem" class="flash-preview" @click.stop>
                <RouterLink :to="`/product/${activityNewItem.spuId}`">
                  <img :src="resolveMediaUrl(activityNewItem.pic)" alt="" />
                  <div class="price">¥{{ formatPrice(activityNewItem.price) }}</div>
                </RouterLink>
              </div>
              <div v-else class="hint">看看最近上架</div>
            </div>
            <div class="web-mini-card">
              <div class="title">热搜词</div>
              <div class="kw-list">
                <RouterLink
                  v-for="kw in hotKeywords.slice(0, 3)"
                  :key="kw"
                  :to="{ path: '/search', query: { q: kw } }"
                >
                  {{ kw }}
                </RouterLink>
              </div>
            </div>
            <div class="web-mini-card">
              <div class="title">全部分类</div>
              <RouterLink to="/category">
                <img
                  v-if="feeds[0]?.pic"
                  :src="resolveMediaUrl(feeds[0].pic)"
                  alt=""
                />
                <div class="hint">按类目逛</div>
              </RouterLink>
            </div>
          </div>
        </section>

        <aside class="web-user-panel">
          <div class="web-user-card">
            <div class="avatar">
              <img
                v-if="auth.customer?.avatar"
                :src="resolveMediaUrl(auth.customer.avatar)"
                alt=""
              />
              <StoreIcon v-else name="user" :size="26" />
            </div>
            <div class="greet">
              {{
                auth.isLoggedIn
                  ? `Hi，${auth.customer?.nickname || '会员'}`
                  : `Hi，欢迎来到${brand.displayName}`
              }}
            </div>
            <button
              v-if="!auth.isLoggedIn"
              type="button"
              class="btn btn-primary btn-block btn-sm"
              @click="onLogin"
            >
              立即登录
            </button>
            <RouterLink v-else class="btn btn-primary btn-block btn-sm" to="/me">
              个人中心
            </RouterLink>
            <div class="quick">
              <RouterLink to="/orders">
                <StoreIcon name="box" :size="20" />
                <span>已买到</span>
              </RouterLink>
              <RouterLink to="/favorites">
                <StoreIcon name="heart" :size="20" />
                <span>收藏夹</span>
              </RouterLink>
              <RouterLink to="/history">
                <StoreIcon name="eye" :size="20" />
                <span>足迹</span>
              </RouterLink>
              <RouterLink to="/after-sales">
                <StoreIcon name="refund" :size="20" />
                <span>售后</span>
              </RouterLink>
            </div>
          </div>
          <div class="web-mini-cart">
            <div class="title">购物清单</div>
            <div v-if="!cartPreview.length" class="hint" style="text-align: left">
              购物车是空的
            </div>
            <RouterLink
              v-for="it in cartPreview"
              :key="String(it.cartItemId)"
              class="line"
              :to="`/product/${it.spuId}`"
            >
              <img :src="resolveMediaUrl(it.pic)" alt="" />
              <div>
                <div style="font-size: 12px; font-weight: 600">{{ it.title }}</div>
                <div class="price" style="font-size: 14px">¥{{ formatPrice(it.price) }}</div>
              </div>
            </RouterLink>
            <RouterLink class="hint" to="/cart">查看购物车 →</RouterLink>
          </div>
        </aside>
      </div>

      <section class="web-feed">
        <div class="web-feed-tabs">
          <button type="button" class="active">猜你喜欢</button>
          <button type="button" @click="router.push('/category')">去分类逛逛</button>
        </div>
        <van-skeleton v-if="feedLoading && !feeds.length" title :row="4" />
        <template v-else-if="feeds.length">
          <div class="web-feed-grid" :class="{ dim: feedLoading }">
            <ProductCard v-for="p in feeds" :key="String(p.spuId)" :item="p" />
          </div>
          <ProductPager
            :page="feedPage"
            :total="feedTotal"
            :page-size="PAGE_SIZE"
            @update:page="onFeedPageChange"
          />
        </template>
        <div v-else class="empty-hint">
          <strong>还没有推荐商品</strong>
        </div>
      </section>
    </template>

    <!-- ========== H5 首页（PRD §8.2：搜索 / Banner / 活动位 / 类目 / 推荐流） ========== -->
    <van-pull-refresh v-else v-model="refreshing" @refresh="onRefresh">
      <header class="h5-sticky">
        <div class="h5-brand-row">
          <img class="h5-brand-logo" :src="resolveMediaUrl(brand.logoUrl)" :alt="brand.displayName" />
          <div class="h5-brand-text">
            <strong>{{ brand.displayName }}</strong>
            <span>{{ brand.slogan }}</span>
          </div>
        </div>
        <button type="button" class="h5-search-bar" @click="goSearch()">
          <span class="h5-search-bar__ph">{{ searchPlaceholder }}</span>
          <span class="h5-search-bar__go">搜索</span>
        </button>
        <div v-if="hotKeywords.length" class="h5-hot">
          <button
            v-for="kw in hotKeywords.slice(0, 6)"
            :key="kw"
            type="button"
            class="h5-hot__tag"
            @click="goSearch(kw)"
          >
            {{ kw }}
          </button>
        </div>
      </header>

      <van-swipe
        v-if="banners.length"
        class="h5-banner"
        :autoplay="4000"
        indicator-color="var(--amber)"
      >
        <van-swipe-item v-for="b in banners" :key="b.id" @click="onBannerClick(b)">
          <img :src="b.picUrl" :alt="b.title" />
        </van-swipe-item>
      </van-swipe>

      <!-- 活动位：仅展示后台已配置的运营位 -->
      <div
        v-if="
          activityHot?.items?.length ||
          activityNew?.items?.length ||
          activityBrand?.items?.length
        "
        class="h5-promo"
      >
        <button
          v-if="activityBrand?.items?.length"
          type="button"
          class="h5-promo__live"
          @click="goPromo('home_promo_live')"
        >
          <span class="badge">活动位</span>
          <img
            v-if="activityBrandItem"
            :src="resolveMediaUrl(activityBrandItem.pic)"
            :alt="activityBrandItem.name"
          />
          <span class="name">
            {{ activityBrandItem?.name || activityBrand?.title || '活动位 · 品牌专区' }}
          </span>
        </button>
        <button
          v-if="activityHot?.items?.length"
          type="button"
          class="h5-promo__card"
          @click="goPromo('home_promo_subsidy')"
        >
          <div class="tit coral">{{ activityHot?.title || '活动位 · 热门精选' }}</div>
          <img
            v-if="activityHotItem"
            :src="resolveMediaUrl(activityHotItem.pic)"
            :alt="activityHotItem.name"
          />
          <div v-if="activityHotItem" class="price">¥{{ formatPrice(activityHotItem.price) }}</div>
        </button>
        <button
          v-if="activityNew?.items?.length"
          type="button"
          class="h5-promo__card"
          @click="goPromo('home_promo_flash')"
        >
          <div class="tit">{{ activityNew?.title || '活动位 · 新品上架' }}</div>
          <img
            v-if="activityNewItem"
            :src="resolveMediaUrl(activityNewItem.pic)"
            :alt="activityNewItem.name"
          />
          <div v-if="activityNewItem" class="price">¥{{ formatPrice(activityNewItem.price) }}</div>
        </button>
      </div>

      <!-- 类目入口 -->
      <div v-if="catLinks.length" class="h5-cats">
        <RouterLink
          v-for="c in catLinks"
          :key="c.id"
          class="h5-cats__item"
          :to="{ path: '/category', query: { categoryId: c.id } }"
        >
          <span class="ico">{{ c.name.slice(0, 1) }}</span>
          <span class="name">{{ c.name }}</span>
        </RouterLink>
        <RouterLink class="h5-cats__item" to="/category">
          <span class="ico more">全</span>
          <span class="name">全部分类</span>
        </RouterLink>
      </div>

      <section class="h5-feed">
        <div class="h5-feed__head">
          <h2>为你推荐</h2>
          <RouterLink v-if="hotSpus?.items?.length" to="/promo/home_hot_spus">热门精选 ›</RouterLink>
        </div>

        <van-list
          v-model:loading="listLoading"
          :finished="listFinished"
          finished-text="没有更多了"
          @load="onLoadMore"
        >
          <div v-if="feeds.length" class="card-grid">
            <ProductCard v-for="p in feeds" :key="String(p.spuId)" :item="p" />
          </div>
          <div v-else-if="!feedLoading" class="empty-hint">
            <strong>还没有推荐商品</strong>
          </div>
        </van-list>
      </section>
    </van-pull-refresh>
  </div>
</template>

<style scoped>
.home--h5 {
  padding-bottom: calc(8px + var(--tabbar-h) + var(--safe-bottom));
  background: #f5f0e8;
  min-height: 100%;
}

.h5-sticky {
  position: sticky;
  top: 0;
  z-index: 20;
  background: var(--card);
  border-bottom: 1px solid var(--border);
  padding: 10px 12px 8px;
}

.h5-brand-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.h5-brand-logo {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  object-fit: contain;
  background: var(--sand);
}
.h5-brand-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.h5-brand-text strong {
  font-size: 16px;
  line-height: 1.2;
}
.h5-brand-text span {
  font-size: 11px;
  color: var(--muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.h5-search-bar {
  width: 100%;
  display: flex;
  align-items: center;
  height: 36px;
  border: 2px solid var(--coral);
  border-radius: 10px;
  background: var(--cream);
  padding: 0;
  overflow: hidden;
  cursor: pointer;
  font: inherit;
  color: inherit;
}
.h5-search-bar__ph {
  flex: 1;
  text-align: left;
  padding: 0 12px;
  font-size: 13px;
  color: var(--muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.h5-search-bar__go {
  flex-shrink: 0;
  height: 100%;
  padding: 0 14px;
  background: var(--coral);
  color: #fff;
  font-weight: 700;
  font-size: 13px;
  display: grid;
  place-items: center;
}

.h5-hot {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}
.h5-hot__tag {
  border: 1px solid var(--border);
  background: var(--sand);
  border-radius: 999px;
  padding: 4px 10px;
  font-size: 12px;
  color: var(--stone);
  cursor: pointer;
}
.h5-hot__tag:first-child {
  color: var(--coral);
  font-weight: 600;
  border-color: rgba(255, 138, 91, 0.35);
}

.h5-banner {
  margin: 10px 12px 0;
  border-radius: 14px;
  overflow: hidden;
}
.h5-banner img {
  width: 100%;
  height: 148px;
  object-fit: cover;
  display: block;
}

.h5-promo {
  display: flex;
  gap: 6px;
  padding: 10px 12px 0;
}
.h5-promo__live,
.h5-promo__card {
  flex: 1;
  min-width: 0;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--card);
  padding: 0;
  overflow: hidden;
  cursor: pointer;
  font: inherit;
  color: inherit;
  text-align: left;
}
.h5-promo__live {
  flex: 1.2;
  position: relative;
}
.h5-promo__live img {
  width: 100%;
  height: 78px;
  object-fit: cover;
  display: block;
}
.h5-promo__live .badge {
  position: absolute;
  top: 6px;
  left: 6px;
  background: var(--brick);
  color: #fff;
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
}
.h5-promo__live .name {
  display: block;
  padding: 6px 8px;
  font-size: 11px;
  color: var(--stone);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.h5-promo__card {
  padding: 8px;
  text-align: center;
}
.h5-promo__card .tit {
  font-size: 12px;
  font-weight: 700;
  margin-bottom: 4px;
}
.h5-promo__card .tit.coral {
  color: var(--coral);
}
.h5-promo__card img {
  width: 56px;
  height: 56px;
  object-fit: cover;
  border-radius: 8px;
  margin: 0 auto;
  display: block;
}
.h5-promo__card .price {
  margin-top: 4px;
  font-size: 13px;
}

.h5-cats {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 4px;
  margin: 10px 8px 0;
  padding: 10px 4px;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 14px;
}
.h5-cats__item {
  text-align: center;
  min-width: 0;
}
.h5-cats__item .ico {
  width: 40px;
  height: 40px;
  margin: 0 auto 4px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  background: rgba(254, 187, 74, 0.25);
  font-size: 14px;
  font-weight: 700;
  color: var(--ink);
}
.h5-cats__item .ico.more {
  background: rgba(139, 92, 246, 0.15);
}
.h5-cats__item .name {
  display: block;
  font-size: 11px;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 0 2px;
}

.h5-feed {
  padding: 12px;
}
.h5-feed__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 10px;
}
.h5-feed__head h2 {
  margin: 0;
  font-size: 16px;
}
.h5-feed__head a {
  font-size: 12px;
  color: var(--stone);
}

.web-promo-banner {
  cursor: pointer;
}
.kw-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
  color: var(--stone);
}
.kw-list a:hover {
  color: var(--coral);
}
.hint {
  font-size: 12px;
  color: var(--slate);
}
.dim {
  opacity: 0.55;
  pointer-events: none;
  transition: opacity 0.2s;
}
.web-promo-tile,
.web-mini-card {
  cursor: pointer;
}
.flash-preview a {
  display: block;
}
</style>
