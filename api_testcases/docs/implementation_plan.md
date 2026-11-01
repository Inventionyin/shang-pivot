# API 接口自动化测试实施计划

> 从「接口文档」到「自动化代码」的完整实施路径  
> 基于 `framework_design.md`（架构）+ `rule.md`（方法论）  
> 更新时间：2026-06-28

---

## 总体路线图

```
现有资产                         Phase 1          Phase 2           Phase 3
───────────────────────────────────────────────────────────────────────────────
OpenAPI 文档        ──→  用例清单        ──→  框架骨架        ──→  用例编码
rule.md (方法论)         (按模块×维度)        (core/helpers/)       (tests/)
framework_design.md                                                 14→140 条

                                        Phase 4          Phase 5
                                   ──→  CI/CD 集成   ──→  持续扩展
                                         (流水线+报告)       (安全/性能/Mock)
```

---

## Phase 1：生成完整用例清单（设计阶段）

### 目标
基于 OpenAPI 接口文档 + `rule.md` 方法论，输出所有接口的用例矩阵清单，供评审确认。

### 输入
- `web/docs/openapi-v3-api-docs(API接口文档).json`（1328 行）
- `web/docs/api-contract(前后端接口契约).md`（273 行）
- `api_testcases/docs/rule.md`（初版方法论；现行规范见 `docs/测试资料/rule.md`）

### 做法
对每个接口按 **6 个维度** 输出用例矩阵：

```
每个接口 = 正向(2-3条) + 反向(3-5条) + 边界(2-3条) + 鉴权(2-3条) + 安全(1-2条)
E2E 场景链路 = 5-6 条
```

### 产出
- `web/api_testcases/test_cases_inventory.md` — 用例清单（预计 ~140 条）

### 模块拆分

| 模块 | 接口数 | 预计用例数 | 覆盖维度 |
|------|--------|-----------|----------|
| 认证与用户 (auth) | 7 | ~25 | 注册/登录/me/地址 CRUD |
| 商品 (products) | 3 | ~18 | 列表搜索/详情/评价 |
| 购物车 (cart) | 3 | ~15 | 添加/更新/删除 |
| 订单 (orders) | 4 | ~22 | 结算/列表/详情/取消 |
| 支付 (payments) | 3 | ~15 | 发起/状态/回调 |
| 售后与评价 (aftersales/reviews) | 4 | ~20 | 申请/列表/取消/发布评价 |
| 营销与会员 (coupons/memberships/points) | 4 | ~18 | 券查询/校验/会员/积分 |
| 消息通知 (notifications) | 3 | ~12 | 列表/单条已读/全部已读 |
| E2E 场景链路 | — | ~6 | 购物全流程/多用户隔离/售后/鉴权 |
| **合计** | **31** | **~145** | — |

---

## Phase 2：搭建框架骨架（基建阶段）

### 目标
按 `framework_design.md` 搭建可运行的框架骨架，确保 1 个模块（auth）的用例能跑通。

### 2.1 目录结构创建（0.5h）

```
web/api_testcases/
├── config/
│   ├── __init__.py
│   ├── settings.py
│   └── env/
│       └── dev.yaml
├── core/
│   ├── __init__.py
│   ├── api_client.py
│   ├── auth_manager.py
│   └── logger.py
├── helpers/
│   ├── __init__.py
│   ├── data_factory.py
│   ├── auth_helper.py
│   └── product_helper.py
├── assertions/
│   ├── __init__.py
│   ├── http_assertions.py
│   └── data_assertions.py
├── fixtures/
│   ├── __init__.py
│   └── api_fixtures.py
├── tests/
│   ├── __init__.py
│   ├── smoke/
│   │   └── test_smoke.py
│   ├── functional/
│   │   ├── test_auth.py
│   │   ├── test_products.py
│   │   ├── test_cart.py
│   │   ├── test_orders.py
│   │   ├── test_payments.py
│   │   └── test_aftersales.py
│   └── e2e/
│       └── test_shopping_flow.py
├── conftest.py
├── pytest.ini
└── requirements.txt
```

### 2.2 核心模块实现（3h）

| 文件 | 功能 | 关键内容 |
|------|------|----------|
| `config/env/dev.yaml` | 环境配置 | base_url, db, redis, timeout |
| `config/settings.py` | 配置加载器 | 多环境切换、环境变量覆盖 |
| `core/api_client.py` | HTTP 客户端 | Session 复用、重试、超时、Idempotency-Key |
| `core/auth_manager.py` | Token 管理器 | 获取、缓存、自动刷新（提前 5 分钟） |
| `core/logger.py` | 结构化日志 | JSON 格式，含 request_id |
| `helpers/data_factory.py` | 数据工厂 | 随机 email、phone、string、uuid |
| `helpers/auth_helper.py` | 认证辅助 | 注册+登录一站式方法 |
| `assertions/http_assertions.py` | HTTP 断言 | ok/created/unauthorized/forbidden/within_time |
| `assertions/data_assertions.py` | 数据断言 | has_fields/field_type/field_positive |
| `fixtures/api_fixtures.py` | 全局 fixture | api_client、auth_token、user_a/user_b |
| `conftest.py` | pytest 配置 | 加载所有 fixture、hook |
| `pytest.ini` | pytest 设置 | markers、testpaths、addopts |

### 2.3 验证标准

- [ ] `pytest tests/smoke/test_smoke.py -v` 通过（5 条冒烟用例）
- [ ] `config/env/dev.yaml` 可正常加载
- [ ] `api_client` fixture 可正常发起 GET/POST/PUT/DELETE
- [ ] `auth_token` fixture 自动注册+登录，返回有效 token
- [ ] HTTP 断言和数据结构断言正常工作

---

## Phase 3：逐模块编码用例（核心阶段）

### 目标
将 Phase 1 的用例清单逐条编码为 pytest 测试用例，按优先级和模块分批交付。

### 3.1 迁移现有用例

将已有的 `pytest/test_ecommerce_api.py`（14 条）按框架标准重构后迁移到 `tests/functional/`：

| 原用例 | 目标文件 |
|--------|----------|
| TestFullShoppingFlow (7 条) | `tests/e2e/test_shopping_flow.py` |
| TestAuthentication (3 条) | `tests/functional/test_auth.py` |
| TestAuthenticatedApis (3 条) | `tests/functional/test_auth.py` + `test_products.py` |
| TestCartOperations (1 条) | `tests/functional/test_cart.py` |

### 3.2 分批编码计划

| 批次 | 模块 | 用例数 | P0/P1/P2/P3 | 预计时间 |
|------|------|--------|-------------|----------|
| **批次 1** | auth + products | ~43 | P0:10, P1:15, P2:15, P3:3 | 3h |
| **批次 2** | cart + orders | ~37 | P0:3, P1:10, P2:18, P3:6 | 3h |
| **批次 3** | payments + aftersales + reviews | ~35 | P0:1, P1:8, P2:18, P3:8 | 3h |
| **批次 4** | coupons + memberships + notifications | ~30 | P0:1, P1:7, P2:14, P3:8 | 2h |
| **批次 5** | E2E 场景链路 | ~6 | P0:2, P1:4 | 2h |

### 3.3 用例编写标准

每个测试用例必须包含：

```python
def test_01_auth_valid_login(self, client, auth_helper):
    """P0: 正向 - 有效账号密码登录"""
    # Arrange
    account = DataFactory.random_email()
    password = "Test@123456"
    auth_helper.register(account, password)

    # Act
    resp = client.post("/v1/auth/login", json={
        "account": account, "password": password
    })

    # Assert
    HttpAssertions.ok(resp)
    data = resp.json()["data"]
    DataAssertions.has_fields(data, "token", "user", "expiresIn")
    assert data["expiresIn"] == 7200
```

### 3.4 验收标准

- [ ] 所有批次用例可通过 `pytest -m "P0 or P1"` 运行
- [ ] 无硬编码数据（全部通过 fixture/data_factory 生成）
- [ ] 每个模块的测试文件可独立运行
- [ ] 失败用例输出清晰的错误信息

---

## Phase 4：CI/CD 集成（自动化阶段）

### 4.1 Makefile

```makefile
.PHONY: smoke regression full security

smoke:
	pytest tests/smoke/ -v --tb=short

regression:
	pytest -m "not perf and not security" -v --alluredir=reports/allure-results

full:
	pytest -v --alluredir=reports/allure-results

security:
	pytest tests/security/ -v

report:
	allure serve reports/allure-results

clean:
	rm -rf reports/allure-results/*
	find . -name "__pycache__" -exec rm -rf {} +
```

### 4.2 GitHub Actions 流水线

```yaml
# .github/workflows/api-tests.yml
name: API Tests
on: [push, pull_request]

jobs:
  smoke:
    runs-on: ubuntu-latest
    services:
      mysql:
        image: mysql:8.0
        env:
          MYSQL_ROOT_PASSWORD: 123456
          MYSQL_DATABASE: web
        ports:
          - 3309:3306
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: '3.13'
      - name: Install dependencies
        run: pip install -r requirements.txt
      - name: Start backend (or use docker)
        run: cd ../ && bash scripts/start_ecommerce.sh backend
      - name: Run smoke tests
        run: make smoke
        env:
          BASE_URL: http://localhost:8080/api

  regression:
    needs: smoke
    if: github.ref == 'refs/heads/main'
    # ...
```

### 4.3 验收标准

- [ ] 每次 push 自动触发冒烟测试
- [ ] main 分支合并触发全量回归
- [ ] Allure 报告可在线查看
- [ ] 失败用例自动通知（企业微信/钉钉/邮件）

---

## Phase 5：持续扩展（进阶阶段）

### 5.1 安全测试 Payload 库（1h）

创建 `config/payloads/security.yaml`，按 `rule.md §5` 填充 SQL 注入、XSS、路径遍历 payload。

### 5.2 Mock 服务（2h）

| Mock 模块 | 文件 | 用途 |
|-----------|------|------|
| 支付网关 | `mocks/mock_payment.py` | 模拟支付宝/微信回调 |
| 短信服务 | `mocks/mock_sms.py` | 拦截短信发送 + 验证码校验 |

### 5.3 数据库断言（1h）

```python
# assertions/db_assertions.py
class DbAssertions:
    def record_exists(self, table: str, **conditions) -> bool: ...
    def record_not_exists(self, table: str, **conditions) -> bool: ...
    def assert_record_count(self, table: str, expected: int, **conditions): ...
```

### 5.4 性能基准（1h）

```python
# tests/performance/test_benchmark.py
def test_product_list_p95(benchmark, client):
    result = benchmark(lambda: client.get("/v1/products?size=20"))
    assert result.elapsed.total_seconds() < 0.5
```

### 5.5 Schema 校验（1.5h）

基于 OpenAPI 规范文件，自动校验响应结构是否符合契约。

---

## 时间估算

| Phase | 内容 | 预计时间 |
|-------|------|----------|
| Phase 1 | 生成用例清单 | 2h |
| Phase 2 | 搭建框架骨架 | 3.5h |
| Phase 3 | 逐模块编码用例（5 个批次） | 13h |
| Phase 4 | CI/CD 集成 | 3h |
| Phase 5 | 持续扩展 | 6h |
| **合计** | — | **27.5h** |

---

## 当前状态

| 模块 | 状态 |
|------|------|
| `framework_design.md` | ✅ 已完成（980 行） |
| `rule.md` | ✅ 已完成（638 行） |
| `test_ecommerce_api.py` | ✅ 已完成（14 条用例，通过） |
| `start_ecommerce.sh` | ✅ 已完成（前后端启动脚本） |
| Phase 1（用例清单） | ⬜ 待开始 |
| Phase 2（框架骨架） | ⬜ 待开始 |
| Phase 3（用例编码） | ⬜ 待开始 |
| Phase 4（CI/CD） | ⬜ 待开始 |
| Phase 5（扩展） | ⬜ 待开始 |

---

> **下一步建议：** 从 Phase 1+2 同时开始 — 生成用例清单 + 搭建框架骨架（config + core + conftest + fixtures），框架就绪后即可进入 Phase 3 大规模编码。