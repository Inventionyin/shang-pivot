# 进度记录：Java + Cucumber + Playwright UI 自动化

## 2026-10-08

### 已完成

1. **前端摸查**：确认商城有 Web/H5 双形态（`>=768px` 分流）、登录表单是原生 input、全站无 `data-testid`、
   token 在 localStorage（`shang_mall_access` / `shang_admin_token`）、两个站点 API 与前端同源。
2. **模块搭建**：`ui_testcases/`（独立 Maven 模块，不挂 backend 聚合）。
   依赖版本对齐：Cucumber 8.0.4 面向 JUnit 6.1.3（其 pom 里 `junit-jupiter.version=6.1.3` 与 Playwright 1.63 一致）。
3. **支撑层**：Env（环境变量 + 启动自检）、BrowserSession（每场景独立 context + trace）、Artifacts（失败留证）、
   ApiClient（造数/清理/取 token）、ScenarioContext（造数登记 + 清理）、Hooks（自检/清理/留证）、
   UiResultsPlugin（结果文件，同构于接口套件以便复用回写器）。
4. **页面对象**：商城登录/首页与详情/购物车与结算/订单；管理端登录与框架/商品列表。
5. **场景 12 条全绿**：`mvn test` → `Tests run: 12, Failures: 0, Errors: 0`，BUILD SUCCESS。
6. **产出**：`ui/cucumber-report.html`（含失败截图）、`ui/<runId>/`（截图+HTML+trace+run.json）、
   `automation_results_ui.json`（带 run_id/时间戳）。

### 实跑踩到的坑（都已修，写进 README §6）

| # | 现象 | 根因 | 处理 |
|---|------|------|------|
| 1 | 5 个 feature 全部解析失败 | Gherkin 标签写在了 `场景:` 之后 | 标签移到场景行之前 |
| 2 | `IllformedLocaleException: zh-CN` | Cucumber 8 用 `Locale.Builder.setLanguage("zh-CN")`，Java 21 拒绝带地区的语言码；gherkin 无裸 `zh` 方言 | 改英文关键字 + 中文正文 |
| 3 | 管理端侧栏点击不跳转 | `.page-title` 在 dashboard 有 2 个 → 严格模式断言**立即失败不重试**，抢在跳转完成前判死 | 改为先 `waitForURL` 再断言内容 |
| 4 | 商品详情找不到 `.sku-row` | 该商品是单 SKU（无规格维度），弹层只有「数量」 | 规格维度为 0 时跳过选规格 |
| 5 | 「未勾选去结算」场景不出现提示 | 我新加的行默认就是勾选态，且旧行多为已勾选 | 勾选态改由接口准备（`prepareCartSelection`） |
| 6 | 造单报"购物车为空" | `ApiClient.get` 没剥 `{code,msg,data}` 信封，`path("items")` 取不到 | get/put/delete 统一走 `unwrap()` |
| 7 | 结算页 13 行商品、金额 ¥88025 | 同上：勾选态没控制住 | 断言"商品清单只有 1 行" + 地址已选 |
| 8 | 搜索场景假绿 | 关键字「露营」正好是默认第一行，搜索失效也会通过 | 换成默认第 3 行的「帐篷」 |

### 一次错误判断（已撤回）

曾据"`GET /admin/products?keyword=x` 返回全部 32 条"判定管理端搜索有缺陷。实际前端
`api/products.ts:41-49` 明确做了 `keyword → name`、`pageSize → size` 映射，**前端是对的**，搜索正常。
教训：只测后端参数名就下结论，没查前端调用层。

### 集成验证

- **禅道回写**：`zentao_sync.py sync-results --results artifacts/automation_results_ui.json` 实测能解析出
  12 个稳定 ID，返回 `missing_case_ids`（禅道暂无这些 UI 用例），**未产生任何误写**。→ 格式兼容，
  要真正回写需先把 UI 用例建档入库。
- **无数据泄漏**：跑完后购物车 13 行（跑前 14；本轮加 4 删 5，多删的那条是同商品的既有测试行），
  最近订单中无 `PENDING_PAY` 残留，本轮造出的订单为 `CLOSED`。

### 下一步

1. UI 用例建档入库（清单 → lint → 上传禅道 → 在线核对），让 UI 结果能进禅道。
2. 接入夜间 CI，并把 UI 结果并入门禁判据。
3. 补场景：支付页、售后、评价、收藏、地址管理；管理端库存/发货/售后审核；H5 形态独立一套。
