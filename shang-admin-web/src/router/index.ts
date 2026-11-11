import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getToken } from '@/utils/request'
import { resolveRoutePermission } from '@/config/nav'
import { hasPermission } from '@/utils/permission'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true, title: '登录' },
    },
    {
      path: '/',
      component: () => import('@/layouts/AdminLayout.vue'),
      redirect: '/dashboard',
      children: [
        {
          path: 'dashboard',
          name: 'dashboard',
          component: () => import('@/views/DashboardView.vue'),
          meta: { title: '工作台', permission: 'dashboard' },
        },
        {
          path: 'products',
          name: 'products',
          component: () => import('@/views/ProductsView.vue'),
          meta: { title: '商品列表', permission: 'products' },
        },
        {
          path: 'products/edit',
          name: 'product-create',
          component: () => import('@/views/ProductEditView.vue'),
          meta: { title: '新增商品', permission: 'product:add' },
        },
        {
          path: 'products/edit/:id',
          name: 'product-edit',
          component: () => import('@/views/ProductEditView.vue'),
          meta: { title: '编辑商品', permission: 'product:edit' },
        },
        {
          path: 'categories',
          name: 'categories',
          component: () => import('@/views/CategoriesView.vue'),
          meta: { title: '类目与品牌', permission: 'categories' },
        },
        // 品牌已在「类目与品牌」页内管理，独立菜单已隐藏；保留路由兼容旧链接
        {
          path: 'brands',
          name: 'brands',
          redirect: '/categories',
          meta: { title: '类目与品牌', permission: 'categories' },
        },
        {
          path: 'inventory',
          name: 'inventory',
          component: () => import('@/views/InventoryView.vue'),
          meta: { title: '库存管理', permission: 'inventory' },
        },
        {
          path: 'in-out',
          name: 'in-out',
          component: () => import('@/views/InOutView.vue'),
          meta: { title: '出入库单', permission: 'in-out' },
        },
        {
          path: 'orders',
          name: 'orders',
          component: () => import('@/views/OrdersView.vue'),
          meta: { title: '订单列表', permission: 'orders' },
        },
        {
          path: 'aftersales',
          name: 'aftersales',
          component: () => import('@/views/AftersalesView.vue'),
          meta: { title: '售后管理', permission: 'aftersales' },
        },
        {
          path: 'reviews',
          name: 'reviews',
          component: () => import('@/views/ReviewsView.vue'),
          meta: { title: '评价管理', permission: 'reviews' },
        },
        {
          path: 'customers',
          name: 'customers',
          component: () => import('@/views/CustomersView.vue'),
          meta: { title: '会员管理', permission: 'customers' },
        },
        {
          path: 'site-brand',
          name: 'site-brand',
          component: () => import('@/views/SiteBrandView.vue'),
          meta: { title: '站点品牌', permission: 'site-brand' },
        },
        {
          path: 'home-slots',
          name: 'home-slots',
          component: () => import('@/views/HomeSlotsView.vue'),
          meta: { title: '首页运营位', permission: 'home-slots' },
        },
        {
          path: 'users',
          name: 'users',
          component: () => import('@/views/UsersView.vue'),
          meta: { title: '用户管理', permission: 'users' },
        },
        {
          path: 'roles',
          name: 'roles',
          component: () => import('@/views/RolesView.vue'),
          meta: { title: '角色管理', permission: 'roles' },
        },
        {
          path: 'menus',
          name: 'menus',
          component: () => import('@/views/MenusView.vue'),
          meta: { title: '菜单与权限', permission: 'menus' },
        },
        {
          path: 'depts',
          name: 'depts',
          component: () => import('@/views/DeptsView.vue'),
          meta: { title: '部门管理', permission: 'depts' },
        },
        {
          path: 'dicts',
          name: 'dicts',
          component: () => import('@/views/DictsView.vue'),
          meta: { title: '字典管理', permission: 'dicts' },
        },
        {
          path: 'logs',
          name: 'logs',
          component: () => import('@/views/LogsView.vue'),
          meta: { title: '操作日志', permission: 'logs' },
        },
        {
          path: 'settings',
          name: 'settings',
          component: () => import('@/views/SettingsView.vue'),
          meta: { title: '系统设置', permission: 'settings' },
        },
        // ===== V3.0 智能客服 =====
        {
          path: 'cs-sessions',
          name: 'cs-sessions',
          component: () => import('@/views/CsSessionsView.vue'),
          meta: { title: '会话管理', permission: 'cs-sessions' },
        },
        {
          path: 'cs-tickets',
          name: 'cs-tickets',
          component: () => import('@/views/CsTicketsView.vue'),
          meta: { title: '客服工单', permission: 'cs-tickets' },
        },
        {
          path: 'cs-knowledge',
          name: 'cs-knowledge',
          component: () => import('@/views/CsKnowledgeView.vue'),
          meta: { title: '知识库', permission: 'cs-knowledge' },
        },
        {
          path: 'cs-quick-replies',
          name: 'cs-quick-replies',
          component: () => import('@/views/CsQuickRepliesView.vue'),
          meta: { title: '快捷回复', permission: 'cs-quick-replies' },
        },
        {
          path: 'cs-seats',
          name: 'cs-seats',
          component: () => import('@/views/CsSeatsView.vue'),
          meta: { title: '坐席管理', permission: 'cs-seats' },
        },
        {
          path: 'cs-stats',
          name: 'cs-stats',
          component: () => import('@/views/CsStatsView.vue'),
          meta: { title: '服务看板', permission: 'cs-stats' },
        },
        {
          path: 'cs-reference-health',
          name: 'cs-reference-health',
          component: () => import('@/views/CsReferenceHealthView.vue'),
          meta: { title: '指代加购健康', permission: 'cs-stats' },
        },
        {
          path: 'customer-service/agent-runs',
          name: 'cs-agent-runs',
          component: () => import('@/views/CsAgentRunsView.vue'),
          meta: { title: 'Agent 运行轨迹', permission: 'cs-agent-runs' },
        },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
  ],
})

router.beforeEach(async (to) => {
  if (to.meta.public) return true
  if (!getToken()) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  const auth = useAuthStore()
  if (auth.token && (!auth.user?.permissions?.length || auth.user?.menus == null)) {
    await auth.loadProfile()
  }

  const need =
    (to.meta.permission as string | undefined) || resolveRoutePermission(to.path)
  if (need && !hasPermission(need)) {
    ElMessage.warning('无访问权限')
    return { path: '/dashboard' }
  }
  return true
})

router.afterEach((to) => {
  const title = (to.meta.title as string) || 'Shang 后台'
  document.title = `${title} · Shang 后台`
})

export default router
