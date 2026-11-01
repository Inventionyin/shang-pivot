<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ProductCard from '@/components/ProductCard.vue'
import {
  fetchHome,
  type HomeSlot,
  type HomeSlotItem,
  type ProductCard as ProductCardType,
} from '@/api/catalog'
import { resolveMediaUrl } from '@/utils/media'
import { navigateSlotLink } from '@/utils/nav'

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const errorMsg = ref('')
const slot = ref<HomeSlot | null>(null)

const code = computed(() => String(route.params.code || ''))

/** 标题取后台运营位配置（运营可改），接口不可用时退回通用文案 */
const pageTitle = computed(() => slot.value?.title || '活动专区')

const items = computed(() => slot.value?.items || [])

const productItems = computed(() =>
  items.value.filter((it) => it.spuId != null && it.spuId !== ''),
)

const linkItems = computed(() =>
  items.value.filter((it) => !it.spuId && (it.linkType || it.imageUrl || it.imageKey || it.title)),
)

function toCard(it: HomeSlotItem): ProductCardType {
  return {
    spuId: it.spuId!,
    name: it.name || it.title || '商品',
    pic: it.pic || it.imageUrl || it.imageKey || null,
    price: Number(it.price ?? 0),
    soldCount: it.soldCount != null ? Number(it.soldCount) : undefined,
  }
}

async function load() {
  if (!code.value) {
    slot.value = null
    errorMsg.value = '无效的频道'
    loading.value = false
    return
  }
  loading.value = true
  errorMsg.value = ''
  try {
    const res = await fetchHome(8)
    const found = (res.data.slots || []).find((s) => s.code === code.value) || null
    slot.value = found
    if (!found) {
      errorMsg.value = '该频道未配置或已下线'
    } else if (!found.items?.length) {
      errorMsg.value = '该频道暂无内容，请在后台运营位中配置'
    }
  } catch {
    slot.value = null
    errorMsg.value = '加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function onLinkClick(it: HomeSlotItem) {
  navigateSlotLink(router, it)
}

onMounted(() => {
  void load()
})

watch(code, () => {
  void load()
})
</script>

<template>
  <div class="page promo-page">
    <div class="promo-head">
      <button type="button" class="back" @click="router.back()">← 返回</button>
      <h1 class="page-title">{{ pageTitle }}</h1>
      <p v-if="slot?.title && slot.title !== pageTitle" class="page-desc">{{ slot.title }}</p>
    </div>

    <van-skeleton v-if="loading" title :row="5" />

    <div v-else-if="errorMsg && !items.length" class="empty-hint">
      <strong>{{ errorMsg }}</strong>
      <RouterLink to="/category">去分类逛逛</RouterLink>
    </div>

    <template v-else>
      <!-- 商品型条目：走商品卡 → 详情 -->
      <div v-if="productItems.length" class="card-grid">
        <ProductCard v-for="it in productItems" :key="String(it.spuId)" :item="toCard(it)" />
      </div>

      <!-- 非商品条目（图片/外链）：按后台 linkType 跳转 -->
      <div v-if="linkItems.length" class="link-grid">
        <button
          v-for="(it, idx) in linkItems"
          :key="`${it.title}-${idx}`"
          type="button"
          class="link-card"
          @click="onLinkClick(it)"
        >
          <div class="link-card__cover">
            <img
              v-if="it.imageUrl || it.imageKey || it.pic"
              :src="resolveMediaUrl(it.imageUrl || it.imageKey || it.pic)"
              :alt="it.title || ''"
            />
            <div v-else class="link-card__placeholder">点击进入</div>
          </div>
          <div class="link-card__body">
            <div class="link-card__title">{{ it.title || '查看详情' }}</div>
            <div class="link-card__hint">
              {{
                String(it.linkType || '').toUpperCase() === 'URL'
                  ? '打开链接'
                  : String(it.linkType || '').toUpperCase() === 'CATEGORY'
                    ? '进入类目'
                    : '查看'
              }}
            </div>
          </div>
        </button>
      </div>

      <p v-if="!productItems.length && !linkItems.length" class="empty-hint">
        <strong>暂无内容</strong>
      </p>
    </template>
  </div>
</template>

<style scoped>
.promo-head {
  margin-bottom: 16px;
}

.back {
  border: 0;
  background: transparent;
  color: var(--stone);
  padding: 0;
  margin-bottom: 8px;
  cursor: pointer;
  font-size: 13px;
}

.back:hover {
  color: var(--coral);
}

.link-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 12px;
  margin-top: 12px;
}

@media (min-width: 768px) {
  .link-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px;
  }
}

.link-card {
  display: flex;
  gap: 14px;
  align-items: stretch;
  text-align: left;
  border: 1px solid var(--border);
  background: var(--card);
  border-radius: 14px;
  padding: 0;
  overflow: hidden;
  cursor: pointer;
  color: inherit;
}

.link-card:hover {
  border-color: var(--amber);
}

.link-card__cover {
  width: 120px;
  min-height: 90px;
  flex-shrink: 0;
  background: var(--sand);
}

.link-card__cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.link-card__placeholder {
  height: 100%;
  min-height: 90px;
  display: grid;
  place-items: center;
  font-size: 12px;
  color: var(--muted);
}

.link-card__body {
  padding: 14px 14px 14px 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 6px;
}

.link-card__title {
  font-size: 15px;
  font-weight: 600;
}

.link-card__hint {
  font-size: 12px;
  color: var(--muted);
}
</style>
