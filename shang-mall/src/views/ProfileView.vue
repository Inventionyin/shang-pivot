<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog } from 'vant'
import StoreIcon from '@/components/StoreIcon.vue'
import type { IconName } from '@/components/storeIcons'
import { useAuthStore } from '@/stores/auth'
import { useCartStore } from '@/stores/cart'
import { useBrandStore } from '@/stores/brand'
import { useClientType } from '@/composables/useClientType'
import { openLoginLayer } from '@/composables/useLoginLayer'
import { maskPhone } from '@/utils/format'
import { resolveMediaUrl } from '@/utils/media'

/** PRD §8.10：头像昵称、订单入口（各状态）、地址 / 收藏 / 足迹 / 售后 / 设置、退出登录 */
const router = useRouter()
const auth = useAuthStore()
const cart = useCartStore()
const brand = useBrandStore()
const { isH5 } = useClientType()

const orderEntries: Array<{ to: string; label: string; icon: IconName }> = [
  { to: '/orders?status=PENDING_PAY', label: '待付款', icon: 'clock' },
  { to: '/orders?status=PENDING_SHIP', label: '待发货', icon: 'box' },
  { to: '/orders?status=PENDING_RECEIVE', label: '待收货', icon: 'truck' },
  // 评价入口在已完成订单（PRD §8.12）
  { to: '/orders?status=COMPLETED', label: '待评价', icon: 'star' },
]

const services: Array<{ to: string; label: string; icon: IconName }> = [
  { to: '/addresses', label: '收货地址', icon: 'pin' },
  { to: '/favorites', label: '我的收藏', icon: 'heart' },
  { to: '/history', label: '浏览足迹', icon: 'eye' },
  { to: '/after-sales', label: '我的售后', icon: 'refund' },
  { to: '/settings', label: '设置', icon: 'settings' },
]

const nickname = computed(() => auth.customer?.nickname || '尚品汇用户')
const phoneText = computed(() => maskPhone(auth.customer?.phone) || brand.slogan)
const avatar = computed(() => resolveMediaUrl(auth.customer?.avatar) || brand.logoUrl)

async function onLogin() {
  await openLoginLayer()
}

async function onLogout() {
  await showConfirmDialog({
    title: '退出登录？',
    message: '退出后需重新用手机号验证码登录，本端登录态会被清除；另一端不受影响。',
  })
  await auth.logout()
  cart.reset()
  await router.replace('/login')
}
</script>

<template>
  <div class="page profile-page" :class="{ 'profile-page--h5': isH5 }">
    <!-- 未登录（PRD §8.10.2）：显示登录按钮，点击拉起登录层 -->
    <div v-if="!auth.isLoggedIn" class="card guest">
      <StoreIcon name="user" :size="40" class="guest__ico" />
      <strong>登录后查看个人中心</strong>
      <p class="hint">订单、收藏、足迹与地址都在这里</p>
      <button type="button" class="btn btn-primary" @click="onLogin">立即登录</button>
    </div>

    <template v-else>
      <!-- 头像昵称 + 手机号脱敏 -->
      <div class="hero">
        <img :src="avatar" alt="" class="avatar" />
        <div class="meta">
          <div class="name">{{ nickname }}</div>
          <div class="muted">{{ phoneText }} · 会员</div>
          <div v-if="!isH5" class="links">
            <RouterLink to="/addresses">收货地址</RouterLink>
            <RouterLink to="/favorites">我的收藏</RouterLink>
            <button type="button" class="link-btn" @click="onLogout">退出登录</button>
          </div>
        </div>
        <button v-if="isH5" type="button" class="btn btn-sm btn-secondary" @click="onLogout">
          退出
        </button>
      </div>

      <!-- 订单入口按状态 -->
      <div class="card order-card">
        <div class="order-card__head">
          <strong>我的订单</strong>
          <RouterLink class="all" to="/orders">查看全部订单 ›</RouterLink>
        </div>
        <div class="order-card__grid">
          <RouterLink v-for="e in orderEntries" :key="e.to" :to="e.to">
            <StoreIcon :name="e.icon" :size="24" />
            <span>{{ e.label }}</span>
          </RouterLink>
        </div>
      </div>

      <!-- 服务入口 -->
      <div class="card service-card">
        <RouterLink v-for="s in services" :key="s.to" class="service-row" :to="s.to">
          <StoreIcon :name="s.icon" :size="18" class="service-row__ico" />
          <span class="service-row__label">{{ s.label }}</span>
          <span class="service-row__arrow">›</span>
        </RouterLink>
      </div>

      <button type="button" class="logout" @click="onLogout">
        <StoreIcon name="logout" :size="16" />
        退出登录
      </button>
      <p class="hint foot">退出仅清除本端登录态，Web 与 H5 各自独立</p>
    </template>
  </div>
</template>

<style scoped>
.guest {
  text-align: center;
  padding: 32px 20px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.guest__ico {
  color: var(--slate);
}

.guest .btn {
  margin-top: 8px;
  min-height: 40px;
}

/* ── 头像昵称 ── */
.hero {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 12px;
  padding: 20px;
  background: linear-gradient(135deg, rgba(254, 187, 74, 0.4), rgba(255, 138, 91, 0.15));
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
}

.avatar {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  object-fit: contain;
  background: var(--card);
  flex-shrink: 0;
}

.meta {
  min-width: 0;
  flex: 1;
}

.name {
  font-size: 20px;
  font-weight: 700;
}

.muted {
  font-size: 13px;
  margin-top: 2px;
}

.links {
  display: flex;
  gap: 14px;
  margin-top: 8px;
  font-size: 12px;
  color: var(--stone);
}

.links a:hover,
.link-btn:hover {
  color: var(--coral);
}

.link-btn {
  border: 0;
  background: transparent;
  padding: 0;
  font: inherit;
  color: inherit;
  cursor: pointer;
}

/* ── 订单入口 ── */
.card {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  box-shadow: var(--sh-1);
}

.order-card {
  padding: 16px 20px;
  margin-bottom: 12px;
}

.order-card__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  font-size: 15px;
}

.order-card__head .all {
  font-size: 12px;
  font-weight: 500;
  color: var(--slate);
}

.order-card__head .all:hover {
  color: var(--coral);
}

.order-card__grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  text-align: center;
}

.order-card__grid a {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--stone);
}

.order-card__grid a:hover {
  color: var(--coral);
}

/* ── 服务入口 ── */
.service-card {
  padding: 4px 0;
  overflow: hidden;
}

.service-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 13px 20px;
  font-size: 14px;
  color: var(--ink);
  border-bottom: 1px solid var(--border);
}

.service-row:last-child {
  border-bottom: 0;
}

.service-row:hover {
  background: var(--cream);
}

.service-row__ico {
  color: var(--stone);
}

.service-row__label {
  flex: 1;
}

.service-row__arrow {
  color: var(--slate);
}

/* ── 退出登录 ── */
.logout {
  width: 100%;
  margin-top: 16px;
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  color: var(--ink);
  font-weight: 600;
  cursor: pointer;
}

.logout:hover {
  border-color: var(--brick);
  color: var(--brick);
}

.foot {
  text-align: center;
  margin-top: 12px;
}

/* ── H5（原型 store/h5/profile.html） ── */
.profile-page--h5 {
  padding: 0 0 calc(8px + var(--tabbar-h) + var(--safe-bottom));
}

.profile-page--h5 .hero {
  margin-bottom: 12px;
  padding: 20px 16px;
  border-radius: 0;
  border-width: 0 0 1px;
  background: linear-gradient(135deg, var(--amber), #ffd88a);
}

.profile-page--h5 .avatar {
  width: 56px;
  height: 56px;
}

.profile-page--h5 .order-card,
.profile-page--h5 .service-card {
  margin: 0 12px 12px;
}

@media (min-width: 768px) {
  .logout {
    max-width: 200px;
  }

  .foot {
    text-align: left;
  }
}
</style>