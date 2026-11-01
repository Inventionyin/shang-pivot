/* Shang 后台统一侧栏 —— 与种子菜单（identity.permission）对齐，按版本裁剪
   用法：页面内 <aside class="sidebar" id="app-sidebar"></aside>
        <script>window.__NAV_ACTIVE__='orders'; window.__NAV_VERSION__='v1'</script>
        <script src="sidebar.js"></script>

   __NAV_VERSION__（默认 v1）：
     v1 = V1.0 交付基线 概览 → 商品中心 → 订单中心 → 系统管理
     v2 = V2.0 增量     多出「出入库单」与「会员与内容」分组

   版本纪律：V1 页面必须用 v1，截图里不允许出现 V2 菜单，否则即版本串档。 */
(function () {
  var active = window.__NAV_ACTIVE__ || '';
  var ver = window.__NAV_VERSION__ || 'v1';
  var I = {
    dashboard: '<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 12l9-9 9 9M5 10v10h14V10"/></svg>',
    products: '<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 7h16l-1 13H5L4 7zM8 7V5a4 4 0 018 0v2"/></svg>',
    categories: '<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="8" width="18" height="12" rx="2"/><path d="M3 14h18"/></svg>',
    inventory: '<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 16V8a2 2 0 00-1-1.73l-7-4a2 2 0 00-2 0l-7 4A2 2 0 003 8v8a2 2 0 001 1.73l7 4a2 2 0 002 0l7-4A2 2 0 0021 16z"/></svg>',
    'stock-io': '<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M7 7h10v10H7zM3 12h4M17 12h4M12 3v4M12 17v4"/></svg>',
    orders: '<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 3h9l4 4v14H6zM15 3v4h4M9 12h6M9 16h6"/></svg>',
    aftersales: '<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 12a9 9 0 109-9 9 9 0 00-9 9zM9 12l2 2 4-4"/></svg>',
    customers: '<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="9" cy="8" r="3.5"/><path d="M2.5 20a6.5 6.5 0 0113 0M17 11a3 3 0 010 6"/></svg>',
    'site-brand': '<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="3"/><path d="M8 14l2.5-3 2 2.5L16 9"/></svg>',
    'home-slots': '<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 4h7v7H4zM13 4h7v4h-7zM13 10h7v10h-7zM4 13h7v7H4z"/></svg>',
    reviews: '<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 3l2.2 4.5 5 .7-3.6 3.5.9 5L12 14.8 7.5 16.7l.9-5L4.8 8.2l5-.7L12 3z"/></svg>',
    users: '<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="9" cy="8" r="3.5"/><path d="M2.5 20a6.5 6.5 0 0113 0"/></svg>',
    roles: '<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="8" r="3.5"/><path d="M5 20a7 7 0 0114 0"/></svg>',
    menus: '<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/></svg>',
    depts: '<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 21V8l9-5 9 5v13M9 21v-6h6v6"/></svg>',
    dicts: '<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 19.5A2.5 2.5 0 016.5 17H20M6.5 2H20v20H6.5A2.5 2.5 0 014 19.5v-15A2.5 2.5 0 016.5 2z"/></svg>',
    logs: '<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M5 12h14M5 8h14M5 16h9"/></svg>',
    settings: '<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M19 5l-2 2M7 17l-2 2"/></svg>'
  };

  /* ---- V1.0 基线（V1 交付，V2 未改动）---- */
  var v1Groups = [
    { title: '概览', items: [
      { key: 'dashboard', label: '工作台', href: 'dashboard.html' }
    ]},
    { title: '商品中心', items: [
      { key: 'products', label: '商品列表', href: 'products.html' },
      { key: 'categories', label: '类目与品牌', href: 'categories.html' },
      { key: 'inventory', label: '库存管理', href: 'inventory.html' }
    ]},
    { title: '订单中心', items: [
      { key: 'orders', label: '订单列表', href: 'orders.html' },
      { key: 'aftersales', label: '售后管理', href: 'aftersales.html' }
    ]},
    { title: '系统管理', items: [
      { key: 'users', label: '用户管理', href: 'users.html' },
      { key: 'roles', label: '角色管理', href: 'roles.html' },
      { key: 'menus', label: '菜单与权限', href: 'menus.html' },
      { key: 'depts', label: '部门管理', href: 'depts.html' },
      { key: 'dicts', label: '字典管理', href: 'dicts.html' },
      { key: 'logs', label: '操作日志', href: 'logs.html' },
      { key: 'settings', label: '系统设置', href: 'settings.html' }
    ]}
  ];

  /* ---- V2.0 增量：商品中心 +出入库单；新增「会员与内容」分组 ---- */
  var v2Groups = v1Groups.map(function (g) {
    if (g.title !== '商品中心') return g;
    return { title: g.title, items: g.items.concat([
      { key: 'stock-io', label: '出入库单', href: 'stock-io.html' }
    ]) };
  }).concat([
    { title: '会员与内容', items: [
      { key: 'customers', label: '会员管理', href: 'customers.html' },
      { key: 'site-brand', label: '站点品牌', href: 'site-brand.html' },
      { key: 'home-slots', label: '首页运营位', href: 'home-slots.html' },
      { key: 'reviews', label: '评价管理', href: 'reviews.html' }
    ]}
  ]).sort(function (a, b) {
    var order = ['概览', '商品中心', '订单中心', '会员与内容', '系统管理'];
    return order.indexOf(a.title) - order.indexOf(b.title);
  });

  var groups = ver === 'v2' ? v2Groups : v1Groups;

  var h = '<div class="brand"><img class="brand-logo" src="logo.png" alt="Shang"><div class="brand-name">Shang 后台</div></div><nav class="nav">';
  groups.forEach(function (g) {
    h += '<div class="nav-group-title">' + g.title + '</div>';
    g.items.forEach(function (it) {
      h += '<a class="nav-item' + (it.key === active ? ' active' : '') + '" href="' + it.href + '">' +
           (I[it.key] || '') + '<span>' + it.label + '</span></a>';
    });
  });
  h += '</nav>';
  var el = document.getElementById('app-sidebar');
  if (el) el.innerHTML = h;
})();