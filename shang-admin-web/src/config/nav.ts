export interface NavItem {
  key: string
  label: string
  path: string
  icon: string
}

export interface NavGroup {
  title: string
  items: NavItem[]
}

/**
 * 静态兜底侧栏（接口 menus 为空时使用）。
 * icon 与种子数据一致：Element Plus 图标组件名。
 */
export const NAV_GROUPS: NavGroup[] = [
  {
    title: '概览',
    items: [{ key: 'dashboard', label: '工作台', path: '/dashboard', icon: 'Odometer' }],
  },
  {
    title: '商品中心',
    items: [
      { key: 'products', label: '商品列表', path: '/products', icon: 'List' },
      { key: 'categories', label: '类目与品牌', path: '/categories', icon: 'Menu' },
      { key: 'inventory', label: '库存管理', path: '/inventory', icon: 'Box' },
      { key: 'in-out', label: '出入库单', path: '/in-out', icon: 'Sort' },
    ],
  },
  {
    title: '订单中心',
    items: [
      { key: 'orders', label: '订单列表', path: '/orders', icon: 'Tickets' },
      { key: 'aftersales', label: '售后管理', path: '/aftersales', icon: 'RefreshLeft' },
    ],
  },
  {
    title: '会员与内容',
    items: [
      { key: 'customers', label: '会员管理', path: '/customers', icon: 'Avatar' },
      { key: 'site-brand', label: '站点品牌', path: '/site-brand', icon: 'Trophy' },
      { key: 'home-slots', label: '首页运营位', path: '/home-slots', icon: 'Grid' },
      { key: 'reviews', label: '评价管理', path: '/reviews', icon: 'ChatDotRound' },
    ],
  },
  {
    title: '系统管理',
    items: [
      { key: 'users', label: '用户管理', path: '/users', icon: 'User' },
      { key: 'roles', label: '角色管理', path: '/roles', icon: 'UserFilled' },
      { key: 'menus', label: '菜单与权限', path: '/menus', icon: 'Grid' },
      { key: 'depts', label: '部门管理', path: '/depts', icon: 'OfficeBuilding' },
      { key: 'dicts', label: '字典管理', path: '/dicts', icon: 'Collection' },
      { key: 'logs', label: '操作日志', path: '/logs', icon: 'Document' },
      { key: 'settings', label: '系统设置', path: '/settings', icon: 'Tools' },
    ],
  },
  {
    title: '智能客服',
    items: [
      { key: 'cs-sessions', label: '会话管理', path: '/cs-sessions', icon: 'ChatDotRound' },
      { key: 'cs-tickets', label: '客服工单', path: '/cs-tickets', icon: 'Tickets' },
      { key: 'cs-knowledge', label: '知识库', path: '/cs-knowledge', icon: 'Collection' },
      { key: 'cs-quick-replies', label: '快捷回复', path: '/cs-quick-replies', icon: 'Comment' },
      { key: 'cs-seats', label: '坐席管理', path: '/cs-seats', icon: 'Headset' },
      { key: 'cs-stats', label: '服务看板', path: '/cs-stats', icon: 'DataAnalysis' },
      { key: 'cs-reference-health', label: '指代加购健康', path: '/cs-reference-health', icon: 'TrendCharts' },
      { key: 'cs-quality', label: '答案质量', path: '/cs-quality', icon: 'ChatLineSquare' },
      {
        key: 'cs-agent-runs',
        label: 'Agent 运行轨迹',
        path: '/customer-service/agent-runs',
        icon: 'Connection',
      },
    ],
  },
]

/** 路由 path → 所需菜单/权限码（与 identity.permission.code 对齐） */
export const ROUTE_PERMISSION: Record<string, string> = {
  '/dashboard': 'dashboard',
  '/products': 'products',
  '/products/edit': 'product:add',
  '/categories': 'categories',
  '/brands': 'categories',
  '/inventory': 'inventory',
  '/in-out': 'in-out',
  '/orders': 'orders',
  '/aftersales': 'aftersales',
  '/reviews': 'reviews',
  '/customers': 'customers',
  '/site-brand': 'site-brand',
  '/home-slots': 'home-slots',
  '/users': 'users',
  '/roles': 'roles',
  '/menus': 'menus',
  '/depts': 'depts',
  '/dicts': 'dicts',
  '/logs': 'logs',
  '/settings': 'settings',
  // V3.0 智能客服
  '/cs-sessions': 'cs-sessions',
  '/cs-tickets': 'cs-tickets',
  '/cs-knowledge': 'cs-knowledge',
  '/cs-quick-replies': 'cs-quick-replies',
  '/cs-seats': 'cs-seats',
  '/cs-stats': 'cs-stats',
  '/cs-reference-health': 'cs-stats',
  '/cs-quality': 'cs-stats',
  '/customer-service/agent-runs': 'cs-agent-runs',
}

export function resolveRoutePermission(path: string): string | undefined {
  if (path.startsWith('/products/edit/')) return 'product:edit'
  if (path.startsWith('/products/edit')) return 'product:add'
  const exact = ROUTE_PERMISSION[path]
  if (exact) return exact
  const seg = '/' + path.replace(/^\//, '').split('/')[0]
  return ROUTE_PERMISSION[seg]
}

export const CRUMB_MAP: Record<string, { group: string; title: string }> = {
  dashboard: { group: '概览', title: '工作台' },
  products: { group: '商品中心', title: '商品列表' },
  'product-edit': { group: '商品中心', title: '编辑商品' },
  categories: { group: '商品中心', title: '类目与品牌' },
  brands: { group: '商品中心', title: '类目与品牌' },
  inventory: { group: '商品中心', title: '库存管理' },
  'in-out': { group: '商品中心', title: '出入库单' },
  orders: { group: '订单中心', title: '订单列表' },
  aftersales: { group: '订单中心', title: '售后管理' },
  reviews: { group: '会员与内容', title: '评价管理' },
  customers: { group: '会员与内容', title: '会员管理' },
  'site-brand': { group: '会员与内容', title: '站点品牌' },
  'home-slots': { group: '会员与内容', title: '首页运营位' },
  users: { group: '系统管理', title: '用户管理' },
  roles: { group: '系统管理', title: '角色管理' },
  menus: { group: '系统管理', title: '菜单与权限' },
  depts: { group: '系统管理', title: '部门管理' },
  dicts: { group: '系统管理', title: '字典管理' },
  logs: { group: '系统管理', title: '操作日志' },
  settings: { group: '系统管理', title: '系统设置' },
  'cs-agent-runs': { group: '智能客服', title: 'Agent 运行轨迹' },
}

export function resolveNavKey(path: string): string {
  if (path.startsWith('/customer-service/agent-runs')) return 'cs-agent-runs'
  if (path.startsWith('/products/edit')) return 'product-edit'
  if (path.startsWith('/products')) return 'products'
  if (path.startsWith('/in-out')) return 'in-out'
  if (path.startsWith('/site-brand')) return 'site-brand'
  if (path.startsWith('/home-slots')) return 'home-slots'
  if (path.startsWith('/customers')) return 'customers'
  if (path.startsWith('/reviews')) return 'reviews'
  const seg = path.replace(/^\//, '').split('/')[0]
  return seg || 'dashboard'
}

export function findNavItem(path: string): NavItem | undefined {
  const key = resolveNavKey(path)
  for (const group of NAV_GROUPS) {
    const hit = group.items.find((item) => item.key === key)
    if (hit) return hit
  }
  return undefined
}

export function resolvePageMeta(path: string, routeTitle?: string): { title: string; icon: string } {
  const nav = findNavItem(path)
  if (nav) {
    return { title: routeTitle || nav.label, icon: nav.icon }
  }
  if (path.startsWith('/products/edit/')) {
    return { title: routeTitle || '编辑商品', icon: 'Edit' }
  }
  if (path.startsWith('/products/edit')) {
    return { title: routeTitle || '新增商品', icon: 'Edit' }
  }
  return { title: routeTitle || path, icon: 'Odometer' }
}

/** 菜单表单可选图标（Element Plus Icons） */
export const MENU_ICON_OPTIONS = [
  'Odometer',
  'Goods',
  'List',
  'Edit',
  'Menu',
  'Medal',
  'Box',
  'Document',
  'Tickets',
  'RefreshLeft',
  'Setting',
  'User',
  'UserFilled',
  'Grid',
  'OfficeBuilding',
  'Collection',
  'Tools',
  'Folder',
  'FolderOpened',
  'Link',
  'Key',
  'Lock',
  'Bell',
  'Calendar',
  'Picture',
  'Upload',
  'Trophy',
  'Avatar',
  'ChatDotRound',
  'Sort',
] as const
