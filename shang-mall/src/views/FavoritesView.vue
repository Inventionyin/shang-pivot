<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { showToast } from 'vant'
import { fetchFavorites, removeFavorite } from '@/api/customer'
import { fetchSpuAvailability, type ProductCard as ProductCardType } from '@/api/catalog'
import StoreIcon from '@/components/StoreIcon.vue'
import { useClientType } from '@/composables/useClientType'
import { formatPrice } from '@/utils/format'
import { resolveMediaUrl } from '@/utils/media'

/**
 * 我的收藏（PRD §8.10.3）：商品收藏列表、可取消收藏、失效商品置灰不可购买。
 * 收藏接口不下发商品状态，因此逐条探测详情接口判定「已下架 / 已售罄」。
 */
const { isH5 } = useClientType()

const loading = ref(true)
const records = ref<ProductCardType[]>([])
/** 失效（已下架或全部规格售罄）的 spuId */
const invalidIds = ref<Set<string>>(new Set())
const pending = ref<ProductCardType | null>(null)
const dialogOpen = ref(false)
const submitting = ref(false)

const popupStyle = { background: 'transparent' }

function spuKey(p: ProductCardType): string {
  return String(p.spuId)
}

function isInvalid(p: ProductCardType): boolean {
  return invalidIds.value.has(spuKey(p))
}

/** 并发受限地探测可售状态，避免一次性打满详情接口 */
async function probeAvailability(list: ProductCardType[]) {
  const queue = [...list]
  const invalid = new Set<string>()
  const workers = Array.from({ length: Math.min(6, queue.length) }, async () => {
    for (;;) {
      const next = queue.shift()
      if (!next) return
      const available = await fetchSpuAvailability(spuKey(next))
      if (!available) invalid.add(spuKey(next))
    }
  })
  await Promise.all(workers)
  invalidIds.value = invalid
}

async function load() {
  loading.value = true
  try {
    const res = await fetchFavorites({ page: 1, size: 50 })
    records.value = (res.data.records as ProductCardType[]) || []
    await probeAvailability(records.value)
  } catch {
    records.value = []
    invalidIds.value = new Set()
  } finally {
    loading.value = false
  }
}

onMounted(load)

function openCancel(item: ProductCardType) {
  pending.value = item
  dialogOpen.value = true
}

async function confirmCancel() {
  const item = pending.value
  if (!item || submitting.value) return
  submitting.value = true
  try {
    await removeFavorite(spuKey(item))
    dialogOpen.value = false
    pending.value = null
    records.value = records.value.filter((p) => spuKey(p) !== spuKey(item))
    showToast('已取消收藏')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="page favorites-page" :class="{ 'favorites-page--h5': isH5 }">
    <h1 class="page-title">我的收藏</h1>

    <van-skeleton v-if="loading" title :row="4" />

    <template v-else-if="records.length">
      <div class="grid" :class="{ 'grid--h5': isH5 }">
        <div
          v-for="p in records"
          :key="spuKey(p)"
          class="fav-card"
          :class="{ 'is-invalid': isInvalid(p) }"
        >
          <RouterLink v-if="!isInvalid(p)" :to="`/product/${spuKey(p)}`" class="cover">
            <img v-if="p.pic" :src="resolveMediaUrl(p.pic)" :alt="p.name" />
            <div v-else class="cover__empty">暂无图片</div>
          </RouterLink>
          <div v-else class="cover" aria-disabled="true">
            <img v-if="p.pic" :src="resolveMediaUrl(p.pic)" :alt="p.name" />
            <div v-else class="cover__empty">暂无图片</div>
          </div>

          <div class="body">
            <div class="title">{{ p.name }}</div>
            <template v-if="isInvalid(p)">
              <div class="invalid">宝贝已失效</div>
              <div class="hint">已下架，不可购买</div>
            </template>
            <div v-else class="price">¥{{ formatPrice(p.price) }}</div>
            <button type="button" class="unfav" @click="openCancel(p)">
              <StoreIcon :name="isInvalid(p) ? 'trash' : 'heart'" :size="14" />
              取消收藏
            </button>
          </div>
        </div>
      </div>
    </template>

    <div v-else class="empty-hint">
      <strong>还没有收藏</strong>
      <p class="hint">喜欢的商品点「收藏」，会出现在这里</p>
      <RouterLink class="btn btn-primary go" to="/category">去逛逛</RouterLink>
    </div>

    <!-- 取消收藏确认：Web 居中对话框 / H5 底部半屏（原型 store/web/favorites.html F-01） -->
    <van-popup
      v-model:show="dialogOpen"
      teleport="body"
      :position="isH5 ? 'bottom' : 'center'"
      :round="isH5"
      :safe-area-inset-bottom="isH5"
      :style="popupStyle"
    >
      <div v-if="pending" class="pop-panel" :class="[isH5 ? 'is-sheet' : 'is-dialog is-sm']">
        <div v-if="isH5" class="sheet-grab" />
        <div class="pop-head">
          <span class="pop-head-ico warn">
            <StoreIcon name="heart" :size="18" />
          </span>
          <span class="pop-title">取消收藏？</span>
          <button type="button" class="pop-x" aria-label="关闭" @click="dialogOpen = false">
            <StoreIcon name="close" :size="16" />
          </button>
        </div>
        <div class="pop-body">
          <div class="dlg-goods">
            <div class="pic">
              <img v-if="pending.pic" :src="resolveMediaUrl(pending.pic)" alt="" />
            </div>
            <div class="meta">
              <div class="t">{{ pending.name }}</div>
              <div class="s">¥{{ formatPrice(pending.price) }}</div>
            </div>
          </div>
          <p class="hint mt">取消后可再次收藏。</p>
        </div>
        <div class="pop-foot">
          <button type="button" class="btn btn-secondary" @click="dialogOpen = false">取消</button>
          <button
            type="button"
            class="btn btn-danger"
            :disabled="submitting"
            @click="confirmCancel"
          >
            取消收藏
          </button>
        </div>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
}

.grid--h5 {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

@media (max-width: 1200px) {
  .grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}

.fav-card {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  overflow: hidden;
  transition: box-shadow 0.15s ease, transform 0.15s ease;
}

.fav-card:hover {
  box-shadow: var(--sh-2);
  transform: translateY(-2px);
}

/* 失效商品置灰（PRD §8.10.3 / 原型 .fav-card）：主图灰度，文案转为灰调 */
.fav-card.is-invalid {
  background: var(--cream);
}

.fav-card.is-invalid .cover {
  filter: grayscale(0.75);
  opacity: 0.65;
}

.fav-card.is-invalid:hover {
  transform: none;
  box-shadow: none;
}

.cover {
  display: block;
  aspect-ratio: 1;
  background: var(--sand);
  cursor: pointer;
}

.fav-card.is-invalid .cover {
  cursor: default;
}

.cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.cover__empty {
  height: 100%;
  display: grid;
  place-items: center;
  color: var(--muted);
  font-size: 13px;
}

.body {
  padding: 10px 12px 14px;
}

.title {
  font-size: 13px;
  font-weight: 500;
  line-height: 1.35;
  margin-bottom: 6px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.invalid {
  color: var(--slate);
  font-size: 12px;
}

.unfav {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-top: 8px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--stone);
  font-size: 12px;
  cursor: pointer;
}

.unfav:hover {
  color: var(--coral);
}

.empty-hint .go {
  margin-top: 12px;
}

.mt {
  margin-top: 12px;
}
</style>