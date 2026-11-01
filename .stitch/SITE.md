# 尚品汇 C 端 — Stitch 站点说明

## Stitch Project

- **Stitch MCP**：当前未配置（见 `.stitch/README.md`）；原型采用 **DESIGN.md 对齐的本地 HTML**（与 V1.0 `admin/` 相同模式）
- **本地原型**：`.stitch/原型/store/` — Web（含收藏）+ H5，暖橙 × 珊瑚 × 奶油
- **Figma 同步**：`generate_figma_design` + 系统浏览器 `open`（勿用 Cursor 内置浏览器，capture 脚本无法提交）
  - 映射表：`capture-manifest.tsv`
  - 辅助脚本：`capture-to-figma.sh <capture-id> <page-path>`
  - Figma 文件：https://www.figma.com/design/1NgQOBqkQGYhFO3bG2z8wz

## 预览

```bash
cd .stitch/原型/store && python3 -m http.server 8766
# http://127.0.0.1:8766/index.html
```

## Sitemap

| Web | H5 | 说明 |
|---|---|---|
| web/home.html | h5/home.html | 首页 |
| web/category.html | h5/category.html | 分类 |
| web/product.html | h5/product.html | 商品详情 |
| web/cart.html | h5/cart.html | 购物车（Web：左清单+右结算） |
| web/checkout.html | h5/checkout.html | 确认订单 |
| web/orders.html | h5/orders.html | 订单 |
| web/profile.html | h5/profile.html | 个人中心（Web：仪表盘） |
| web/favorites.html | — | 我的收藏（Web） |
| web/login.html | h5/login.html | 登录 |

## 多端策略

- **V2.0**：Web 桌面 + H5 移动 Web
- **后续**：微信小程序（布局参考 H5 + 微信登录）

## PRD

`docs/01-requirements/PRD-V2.0-购物网站.md`（C 端 §9.3；**后台补全图文 §9.4**）

## 后台补全原型（V2.0）

```bash
cd .stitch/原型/admin && python3 -m http.server 8765
# http://127.0.0.1:8765/index.html
# 增量：customers / site-brand / home-slots / reviews / stock-io + settings·交易 / orders 增强 / 定时上架
```
