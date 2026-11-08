import { createRouter, createWebHistory } from 'vue-router'
import { getAccessToken } from '@/utils/request'
import { useAuthStore } from '@/stores/auth'
import { openLoginLayer } from '@/composables/useLoginLayer'

/** 仅“用户主动从站内入口进入 AI”时置位；直接打开/收藏的 /ai 会被导回商品首页。 */
const AI_ENTRY_KEY = 'shangpin:ai-entry'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true, title: '登录', hideTab: true },
    },
    {
      path: '/',
      component: () => import('@/layouts/MallLayout.vue'),
      children: [
        {
          path: '',
          name: 'home',
          component: () => import('@/views/HomeView.vue'),
          meta: { public: true, title: '首页', tab: 'home' },
        },
        {
          path: 'promo/:code',
          name: 'promo',
          component: () => import('@/views/PromoView.vue'),
          meta: { public: true, title: '活动专区', hideTab: true },
        },
        {
          path: 'category',
          name: 'category',
          component: () => import('@/views/CategoryView.vue'),
          meta: { public: true, title: '分类', tab: 'category' },
        },
        {
          path: 'product/:spuId',
          name: 'product',
          component: () => import('@/views/ProductView.vue'),
          meta: { public: true, title: '商品详情', hideTab: true },
        },
        {
          path: 'search',
          name: 'search',
          component: () => import('@/views/SearchView.vue'),
          meta: { public: true, title: '搜索', hideTab: true },
        },
        {
          path: 'cart',
          name: 'cart',
          component: () => import('@/views/CartView.vue'),
          // PRD §8.0 / §8.6：游客可浏览购物车，结算时再拉起登录层
          meta: { public: true, title: '购物车', tab: 'cart' },
        },
        {
          path: 'ai',
          name: 'ai-chat',
          component: () => import('@/views/AiChatView.vue'),
          // V3.0 智能客服：须登录（PRD-V3.0 §8.1），路由守卫拉起登录层
          meta: { title: 'AI 助手', auth: true },
        },
        {
          path: 'checkout',
          name: 'checkout',
          component: () => import('@/views/CheckoutView.vue'),
          meta: { title: '确认订单', hideTab: true, auth: true },
        },
        {
          path: 'orders',
          name: 'orders',
          component: () => import('@/views/OrdersView.vue'),
          meta: { title: '我的订单', hideTab: true, auth: true },
        },
        {
          path: 'orders/:id',
          name: 'order-detail',
          component: () => import('@/views/OrderDetailView.vue'),
          meta: { title: '订单详情', hideTab: true, auth: true },
        },
        {
          path: 'pay/:orderId',
          name: 'pay',
          component: () => import('@/views/PayView.vue'),
          meta: { title: '支付', hideTab: true, auth: true },
        },
        {
          path: 'me',
          name: 'me',
          component: () => import('@/views/ProfileView.vue'),
          meta: { title: '我的', tab: 'me', auth: true },
        },
        {
          path: 'settings',
          name: 'settings',
          component: () => import('@/views/SettingsView.vue'),
          meta: { title: '设置', hideTab: true, auth: true },
        },
        {
          path: 'addresses',
          name: 'addresses',
          component: () => import('@/views/AddressView.vue'),
          meta: { title: '收货地址', hideTab: true, auth: true },
        },
        {
          path: 'favorites',
          name: 'favorites',
          component: () => import('@/views/FavoritesView.vue'),
          meta: { title: '我的收藏', hideTab: true, auth: true },
        },
        {
          path: 'history',
          name: 'history',
          component: () => import('@/views/HistoryView.vue'),
          meta: { title: '浏览足迹', hideTab: true, auth: true },
        },
        {
          path: 'after-sales',
          name: 'after-sales',
          component: () => import('@/views/AfterSalesView.vue'),
          meta: { title: '我的售后', hideTab: true, auth: true },
        },
        {
          path: 'after-sales/apply',
          name: 'after-sale-apply',
          component: () => import('@/views/AfterSaleApplyView.vue'),
          meta: { title: '申请售后', hideTab: true, auth: true },
        },
        {
          path: 'after-sales/:id',
          name: 'after-sale-detail',
          component: () => import('@/views/AfterSaleDetailView.vue'),
          meta: { title: '售后详情', hideTab: true, auth: true },
        },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async (to, from) => {
  // 默认落地页必须是商品首页：直接打开/恢复的 /ai 不带站内入口标记时回首页。
  // 站内点击 AI 入口时 from.name 有值，正常放行；进入后写标记，支持在 AI 页刷新。
  // 平台“人工体验”入口带 ?test=human，属于显式入口，允许直接打开。
  const humanTestEntry = to.name === 'ai-chat' && to.query.test === 'human'
  if (to.name === 'ai-chat' && !humanTestEntry && !from.name && sessionStorage.getItem(AI_ENTRY_KEY) !== '1') {
    return { path: '/', replace: true }
  }

  const auth = useAuthStore()
  if (!auth.bootstrapped) {
    await auth.bootstrap()
  }

  const needAuth = to.matched.some((r) => r.meta.auth)
  if (needAuth && !getAccessToken()) {
    // 首次进入（深链）退化为独立登录页，保留 redirect；页内跳转拉起登录层，成功后回原场景
    if (!from.name) {
      return {
        path: '/login',
        query: { redirect: to.fullPath },
      }
    }
    const ok = await openLoginLayer()
    if (!ok) return false
  }

  const title = (to.meta.title as string) || ''
  if (title) document.title = title
  return true
})

router.afterEach((to) => {
  if (to.name === 'ai-chat') {
    sessionStorage.setItem(AI_ENTRY_KEY, '1')
  } else {
    sessionStorage.removeItem(AI_ENTRY_KEY)
  }
})

export default router
