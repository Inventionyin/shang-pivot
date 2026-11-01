/**
 * 原型截图渲染脚本 —— 把 .stitch/原型 下的 HTML 渲染进 docs/01-requirements/shots/
 *
 * 用法：node .stitch/原型/render-shots.mjs [--only=<substr>] [--scale=2]
 *
 * 依赖全局 playwright（已装：/usr/local/lib/node_modules/playwright）。
 * 版本纪律：V1 页面必须来自 __NAV_VERSION__='v1' 的原型，V2 增量页面用 `p2-` 前缀，
 *          两类截图不得互相混入对方的菜单与区块。
 * 截图纪律：带 data-demo-only 的元素（原型的手动触发块）在截图时被隐藏 —— 图里只应有产品 UI。
 */
import { chromium } from '/usr/local/lib/node_modules/playwright/index.mjs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const HERE = path.dirname(fileURLToPath(import.meta.url)) // .stitch/原型
const ROOT = path.resolve(HERE, '../..')
const SHOTS = path.join(ROOT, 'docs/01-requirements/shots')
const PROTO = 'file://' + HERE

const ADMIN = { w: 1440, h: 1150 } // 后台：侧栏 + 顶栏 + 内容
const WEB = { w: 1440, h: 900 } // C 端 Web
const H5 = { w: 390, h: 844 } // C 端 H5
const SHEET = { w: 900, h: 1250 } // 弹窗图鉴页（文档版式）

/** @type {{out:string, file:string, view:{w:number,h:number}, wait?:number}[]} */
const JOBS = [
  // ===== V1.0 后台（v1 侧栏：概览 / 商品中心 / 订单中心 / 系统管理）=====
  { out: 'login-fixed.png', file: 'admin/login.html', view: ADMIN },
  { out: 'dashboard.png', file: 'admin/dashboard.html', view: ADMIN },
  { out: 'products.png', file: 'admin/products.html', view: ADMIN },
  { out: 'product-edit.png', file: 'admin/product-edit.html', view: ADMIN },
  { out: 'categories.png', file: 'admin/categories.html', view: ADMIN },
  { out: 'inventory.png', file: 'admin/inventory.html', view: ADMIN },
  { out: 'orders.png', file: 'admin/orders.html', view: ADMIN },
  { out: 'aftersales.png', file: 'admin/aftersales.html', view: ADMIN },
  { out: 'users.png', file: 'admin/users.html', view: ADMIN },
  { out: 'roles.png', file: 'admin/roles.html', view: ADMIN },
  { out: 'menus.png', file: 'admin/menus.html', view: ADMIN },
  { out: 'depts.png', file: 'admin/depts.html', view: ADMIN },
  { out: 'dicts.png', file: 'admin/dicts.html', view: ADMIN },
  { out: 'logs.png', file: 'admin/logs.html', view: ADMIN },
  { out: 'settings.png', file: 'admin/settings.html', view: ADMIN },
  { out: 'dialogs.png', file: 'admin/dialogs.html', view: ADMIN, wait: 400 },

  // ===== V2.0 后台增量（v2 侧栏：多出「出入库单」与「会员与内容」）=====
  { out: 'p2-customers.png', file: 'admin/customers.html', view: ADMIN },
  { out: 'p2-site-brand.png', file: 'admin/site-brand.html', view: ADMIN },
  { out: 'p2-home-slots.png', file: 'admin/home-slots.html', view: ADMIN },
  { out: 'p2-reviews.png', file: 'admin/reviews.html', view: ADMIN },
  { out: 'p2-stock-io.png', file: 'admin/stock-io.html', view: ADMIN },
  { out: 'p2-settings-trade.png', file: 'admin/settings-trade.html', view: ADMIN },
  { out: 'p2-orders.png', file: 'admin/orders-enhanced.html', view: ADMIN },
  { out: 'p2-product-edit-schedule.png', file: 'admin/product-edit-schedule.html', view: ADMIN },
  { out: 'p2-dialogs.png', file: 'admin/dialogs-v2.html', view: ADMIN, wait: 400 },

  // ===== V2.0 尚品汇 C 端 Web（1440）=====
  { out: 'store-web-home.png', file: 'store/web/home.html', view: WEB },
  { out: 'store-web-category.png', file: 'store/web/category.html', view: WEB },
  { out: 'store-web-product.png', file: 'store/web/product.html', view: WEB },
  { out: 'store-web-cart.png', file: 'store/web/cart.html', view: WEB },
  { out: 'store-web-checkout.png', file: 'store/web/checkout.html', view: WEB },
  { out: 'store-web-orders.png', file: 'store/web/orders.html', view: WEB },
  { out: 'store-web-profile.png', file: 'store/web/profile.html', view: WEB },
  { out: 'store-web-favorites.png', file: 'store/web/favorites.html', view: WEB },
  { out: 'store-web-login.png', file: 'store/web/login.html', view: WEB },
  { out: 'store-web-dialogs.png', file: 'store/web/dialogs.html', view: SHEET, wait: 400 },

  // ===== V2.0 尚品汇 C 端 H5（390×844）=====
  { out: 'store-h5-home.png', file: 'store/h5/home.html', view: H5 },
  { out: 'store-h5-category.png', file: 'store/h5/category.html', view: H5 },
  { out: 'store-h5-product.png', file: 'store/h5/product.html', view: H5 },
  { out: 'store-h5-cart.png', file: 'store/h5/cart.html', view: H5 },
  { out: 'store-h5-checkout.png', file: 'store/h5/checkout.html', view: H5 },
  { out: 'store-h5-orders.png', file: 'store/h5/orders.html', view: H5 },
  { out: 'store-h5-profile.png', file: 'store/h5/profile.html', view: H5 },
  { out: 'store-h5-login.png', file: 'store/h5/login.html', view: H5 },
  // 图鉴页是文档版式（宽幅 2 列），不是 H5 真机页 → 用宽视口截全
  { out: 'store-h5-dialogs.png', file: 'store/h5/dialogs.html', view: SHEET, wait: 400 },
]

const only = process.argv.find((a) => a.startsWith('--only='))?.slice(7)
const scale = Number(process.argv.find((a) => a.startsWith('--scale='))?.slice(8) || 2)

const jobs = only ? JOBS.filter((j) => j.out.includes(only) || j.file.includes(only)) : JOBS
if (!jobs.length) {
  console.error('no jobs matched --only=', only)
  process.exit(1)
}

const browser = await chromium.launch()
const ctx = await browser.newContext({ deviceScaleFactor: scale })
let failed = 0

for (const job of jobs) {
  const page = await ctx.newPage()
  await page.setViewportSize({ width: job.view.w, height: job.view.h })
  const problems = []
  page.on('console', (m) => m.type() === 'error' && problems.push(m.text()))
  page.on('pageerror', (e) => problems.push(String(e)))
  page.on('requestfailed', (r) => {
    // 只关心本地资源；外部字体拿不到不算失败
    if (r.url().startsWith('file://') || r.url().startsWith('http://127.0.0.1')) {
      problems.push(`requestfailed ${r.url()}`)
    }
  })

  try {
    await page.goto(`${PROTO}/${job.file}`, { waitUntil: 'load' })
    // 截图只保留产品界面：隐藏原型自带的「演示触发」等调试块（data-demo-only）。
    // 这些块是为了在原型里手动打开各个弹窗分支，不属于产品 UI，不该进 PRD 图。
    await page.addStyleTag({ content: '[data-demo-only]{display:none !important}' })
    await page.waitForTimeout(job.wait ?? 250)
    await page.screenshot({ path: path.join(SHOTS, job.out) })
    const ok = problems.length === 0
    if (!ok) failed++
    console.log(`${ok ? 'ok  ' : 'WARN'}  ${job.out.padEnd(30)} ${job.view.w}x${job.view.h}@${scale}  ${path.basename(job.file)}`)
    problems.slice(0, 4).forEach((p) => console.log(`        ! ${p.slice(0, 160)}`))
  } catch (e) {
    failed++
    console.log(`FAIL  ${job.out}  ${e.message}`)
  } finally {
    await page.close()
  }
}

await browser.close()
console.log(`\n${jobs.length - failed}/${jobs.length} rendered into docs/01-requirements/shots/`)
process.exit(failed ? 1 : 0)