# 任务计划：Java + Cucumber + Playwright UI 自动化

> 目录：`.planning/2026-10-08-java-ui-automation/`。一个任务只保留这一套。
> 目标：在 `shang-pivot-master/ui_testcases/` 落地一套可运行的 Java UI 自动化（Cucumber 8 + Playwright 1.63 + JUnit 6），
> 参考现有 `api_testcases/tests/ui/test_frontend_smoke.py`（薄层）与整套流水线约定（环境变量传凭据、失败留证、不假绿）。

## 背景事实（已核实）

- 现有 UI 层只有 41 行 pytest，默认地址还是元气购的旧 IP，`RUN_UI!=1` 就 skip（有假绿风险）。
- 两个前端：`shang-mall`（Vant 4，60084）、`shang-admin-web`（Element Plus，60085）。**全站无 data-testid**。
- 商城有 Web/H5 两套 DOM，**由视口宽度 ≥768px 切换** → 测试必须锁定 viewport。
- 登录凭据：商城手机号 13800000001 + 固定验证码 123456；管理端 admin / Admin@123。
- Token 在 localStorage：`shang_mall_access` / `shang_admin_token`（可注入绕过 UI 登录）。
- 部署环境 API 与前端同源（nginx 反代）。CORS 已于 2026-10-08 修复。

## 阶段

| # | 阶段 | 产出 | 状态 |
|---|------|------|------|
| 1 | 依赖与版本确认 | playwright 1.63.0 / cucumber 8.0.4 / junit 6.1.3 对齐 | ✅ |
| 2 | 模块骨架 + pom | `ui_testcases/pom.xml` | ✅ |
| 3 | 核心支撑 | Env / Artifacts / BrowserSession / ApiClient / ScenarioContext / Hooks / UiResultsPlugin | ✅ |
| 4 | Page Object | 商城 4 个 + 管理端 2 个 | ✅ |
| 5 | Steps + Feature | 英文关键字 + 中文正文，12 条场景各带稳定 ID | ✅ |
| 6 | 装浏览器 + 跑通 | chromium 已装；`mvn test` → 12/12 绿 | ✅ |
| 7 | 留痕与集成 | README、run.json 摘要、结果文件；禅道回写格式已实测兼容 | ✅ |

## 唯一 Next Step

（本任务已完成）下一步：给 UI 用例建档入库，让 UI 结果能真正回写禅道；其余候选见 `progress.md` 末尾。
