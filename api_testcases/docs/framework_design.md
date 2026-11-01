# API 接口自动化测试框架设计

> 完整的接口自动化测试框架应具备的功能与架构方案  
> 目标：可维护、可扩展、支持 CI/CD 集成

---

## 目录

1. [框架总体架构](#1-框架总体架构)
2. [目录结构设计](#2-目录结构设计)
3. [配置管理](#3-配置管理)
4. [API 客户端层](#4-api-客户端层)
5. [认证与 Token 管理](#5-认证与-token-管理)
6. [测试数据管理](#6-测试数据管理)
7. [断言与校验](#7-断言与校验)
8. [Schema 校验与契约测试](#8-schema-校验与契约测试)
9. [报告与日志](#9-报告与日志)
10. [CI/CD 集成](#10-cicd-集成)
11. [Mock 与服务虚拟化](#11-mock-与服务虚拟化)
12. [性能与压力测试](#12-性能与压力测试)
13. [安全测试集成](#13-安全测试集成)
14. [数据库与中间件验证](#14-数据库与中间件验证)
15. [实施路线图](#15-实施路线图)

---

## 1. 框架总体架构

```
┌─────────────────────────────────────────────────────────┐
│                    测试层 (Test Layer)                     │
│  test_pos.py  test_neg.py  test_e2e.py  test_perf.py     │
├─────────────────────────────────────────────────────────┤
│                 业务层 (Business Layer)                    │
│   AuthHelper   ProductHelper   CartHelper   OrderHelper  │
├─────────────────────────────────────────────────────────┤
│                   核心层 (Core Layer)                     │
│  ApiClient   AuthManager   Assertions   DataFactory     │
│  SchemaValidator   RetryHandler   Reporter              │
├─────────────────────────────────────────────────────────┤
│                   配置层 (Config Layer)                    │
│  settings.py   env.yaml   test_data.yaml   db_config     │
├─────────────────────────────────────────────────────────┤
│                    基础设施 (Infra)                       │
│  CI/CD Pipeline   Docker   Allure   Prometheus           │
└─────────────────────────────────────────────────────────┘
```

**核心设计原则：**
- **分层解耦**：每一层只依赖下一层，不跨层调用
- **单一职责**：每个模块只做一件事
- **配置驱动**：环境、URL、账号等通过配置管理，不硬编码
- **数据与逻辑分离**：测试数据独立于测试代码

---

## 2. 目录结构设计

```
api_testcases/
├── pytest.ini                   # pytest 全局配置
├── conftest.py                  # 全局 fixture（token、client、db）
├── requirements.txt             # Python 依赖
├── Makefile                     # 常用命令快捷方式
│
├── config/                      # 配置目录
│   ├── __init__.py
│   ├── settings.py              # 配置加载器（环境变量 + YAML）
│   ├── env/                     # 环境配置
│   │   ├── dev.yaml             # 开发环境
│   │   ├── staging.yaml         # 预发布环境
│   │   └── prod.yaml            # 生产环境（读操作）
│   └── test_data/               # 测试数据模板
│       ├── users.yaml           # 测试用户数据
│       ├── products.yaml        # 商品测试数据
│       └── payloads/            # 请求模板
│           ├── login.json
│           └── checkout.json
│
├── core/                        # 框架核心
│   ├── __init__.py
│   ├── api_client.py            # HTTP 客户端封装（requests/session）
│   ├── auth_manager.py          # Token 获取、缓存、刷新
│   ├── retry_handler.py         # 重试策略
│   ├── schema_validator.py      # JSON Schema / OpenAPI 校验
│   └── logger.py                # 日志配置
│
├── helpers/                     # 业务辅助层
│   ├── __init__.py
│   ├── auth_helper.py           # 注册、登录、Token 管理
│   ├── product_helper.py        # 商品创建、查询
│   ├── cart_helper.py           # 购物车操作
│   ├── order_helper.py          # 下单、订单查询、取消
│   ├── address_helper.py        # 地址管理
│   └── data_factory.py          # 随机测试数据生成器
│
├── assertions/                  # 断言模块
│   ├── __init__.py
│   ├── http_assertions.py       # HTTP 状态码、Header 断言
│   ├── data_assertions.py       # 响应数据结构断言
│   ├── business_assertions.py   # 业务规则断言
│   └── db_assertions.py         # 数据库断言
│
├── tests/                       # 测试用例
│   ├── __init__.py
│   ├── smoke/                   # 冒烟测试（P0）
│   │   └── test_smoke.py
│   ├── functional/              # 功能测试
│   │   ├── test_auth.py
│   │   ├── test_products.py
│   │   ├── test_cart.py
│   │   ├── test_orders.py
│   │   ├── test_payments.py
│   │   └── test_aftersales.py
│   ├── e2e/                     # 端到端场景
│   │   ├── test_shopping_flow.py
│   │   └── test_user_isolation.py
│   ├── security/                # 安全测试
│   │   ├── test_sql_injection.py
│   │   └── test_xss.py
│   └── performance/             # 性能测试
│       └── test_load.py
│
├── fixtures/                    # 自定义 fixtures
│   ├── __init__.py
│   ├── api_fixtures.py          # API client、session
│   ├── data_fixtures.py         # 测试账号、测试资源
│   └── db_fixtures.py           # 数据库连接
│
├── schemas/                     # JSON Schema / OpenAPI 定义
│   ├── openapi.yaml             # OpenAPI 3.0 规范文件
│   └── schemas/                 # JSON Schema 文件
│       ├── login_response.json
│       └── product_response.json
│
├── mocks/                       # Mock 服务
│   ├── __init__.py
│   ├── mock_payment.py          # 支付网关 Mock
│   └── mock_sms.py              # 短信服务 Mock
│
├── db/                          # 数据库辅助
│   ├── __init__.py
│   ├── db_client.py             # 数据库连接
│   └── db_seed.py               # 数据初始化脚本
│
├── scripts/                     # 运维脚本
│   ├── run_smoke.sh             # 冒烟测试脚本
│   ├── run_regression.sh        # 回归测试脚本
│   ├── seed_data.py             # 初始化测试数据
│   └── cleanup_data.py          # 清理测试数据
│
├── reports/                     # 报告输出
│   ├── allure-results/          # Allure 原始结果
│   └── html/                    # pytest-html 报告
│
└── logs/                        # 运行日志
    └── test_run.log
```

---

## 3. 配置管理

### 3.1 多环境支持

```yaml
# config/env/dev.yaml
base_url: http://localhost:8080/api
db:
  host: localhost
  port: 3309
  database: web
  user: root
  password: 123456
redis:
  host: localhost
  port: 6379
timeout: 30
auth:
  token_expiry: 7200
```

### 3.2 配置加载器

```python
# config/settings.py
import os
import yaml

class Settings:
    def __init__(self):
        env = os.getenv("TEST_ENV", "dev")
        config_path = f"config/env/{env}.yaml"
        with open(config_path) as f:
            self._config = yaml.safe_load(f)

    @property
    def base_url(self) -> str:
        return os.getenv("BASE_URL", self._config["base_url"])

    @property
    def db_config(self) -> dict:
        return self._config["db"]

    @property
    def timeout(self) -> int:
        return self._config.get("timeout", 30)


settings = Settings()
```

**环境切换方式：**
```bash
TEST_ENV=staging pytest tests/
BASE_URL=http://custom:8080/api pytest tests/
```

### 3.3 敏感信息管理

- 密码、Token、API Key 通过环境变量注入，不写入代码
- 支持 `.env` 文件（不入 Git）
- CI 中通过 Secret Manager 注入

---

## 4. API 客户端层

### 4.1 核心功能

| 功能 | 说明 |
|------|------|
| **Session 管理** | 复用 TCP 连接，自动管理 Cookie |
| **自动重试** | 网络错误/5xx 自动重试（可配置次数和间隔） |
| **超时控制** | 连接超时 + 读取超时分别设置 |
| **请求拦截** | 自动添加公共 Header（Content-Type、X-Request-Id） |
| **响应拦截** | 统一状态码检查、响应时间记录 |
| **日志记录** | 自动记录请求/响应的 curl 等价命令 |

### 4.2 实现示例

```python
# core/api_client.py
import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

class ApiClient:
    """API 客户端封装"""

    def __init__(self, base_url: str, token: str = None, timeout: int = 30):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.session = requests.Session()

        # 重试策略
        retry = Retry(
            total=3,
            backoff_factor=0.5,
            status_forcelist=[500, 502, 503, 504],
            allowed_methods=["GET", "HEAD", "OPTIONS"]
        )
        adapter = HTTPAdapter(max_retries=retry, pool_connections=10, pool_maxsize=20)
        self.session.mount("http://", adapter)
        self.session.mount("https://", adapter)

        # 默认 Header
        self.session.headers.update({
            "Content-Type": "application/json",
            "X-Client-Version": "1.0.0",
        })
        if token:
            self.session.headers["Authorization"] = f"Bearer {token}"

    def _url(self, path: str) -> str:
        return f"{self.base_url}{path}"

    def get(self, path: str, params: dict = None, **kwargs) -> requests.Response:
        resp = self.session.get(self._url(path), params=params,
                                timeout=self.timeout, **kwargs)
        return resp

    def post(self, path: str, json: dict = None, data: dict = None,
             idempotency_key: str = None, **kwargs) -> requests.Response:
        headers = {}
        if idempotency_key:
            headers["Idempotency-Key"] = idempotency_key
        resp = self.session.post(self._url(path), json=json, data=data,
                                 headers=headers, timeout=self.timeout, **kwargs)
        return resp

    def put(self, path: str, json: dict = None, **kwargs) -> requests.Response:
        return self.session.put(self._url(path), json=json,
                                timeout=self.timeout, **kwargs)

    def delete(self, path: str, **kwargs) -> requests.Response:
        return self.session.delete(self._url(path), timeout=self.timeout, **kwargs)

    def close(self):
        self.session.close()
```

### 4.3 请求追踪

每次请求自动生成 `X-Request-Id`，并记录日志：

```python
import uuid
import logging

logger = logging.getLogger(__name__)

def _log_request(resp):
    request_id = resp.request.headers.get("X-Request-Id", "N/A")
    logger.info(f"[{request_id}] {resp.request.method} {resp.url} → {resp.status_code} ({resp.elapsed.total_seconds():.3f}s)")
```

---

## 5. 认证与 Token 管理

### 5.1 Token 生命周期管理

```
┌──────────┐     ┌──────────────┐     ┌──────────────┐
│ 获取Token │ ──→ │ 缓存（内存）  │ ──→ │ 过期自动刷新  │
└──────────┘     └──────────────┘     └──────────────┘
```

### 5.2 实现设计

```python
# core/auth_manager.py
import time
import requests

class AuthManager:
    """Token 管理器：获取、缓存、自动刷新"""

    def __init__(self, base_url: str):
        self.base_url = base_url
        self._token: str = None
        self._expires_at: float = 0
        self._user_id: int = None

    def get_token(self) -> str:
        """获取有效 Token，自动刷新过期 Token"""
        if self._token and time.time() < self._expires_at - 300:  # 提前 5 分钟刷新
            return self._token
        return self._login()

    def _login(self) -> str:
        """登录获取新 Token"""
        # 从配置/环境变量获取测试账号
        # ...
        resp = requests.post(f"{self.base_url}/v1/auth/login", json={...})
        data = resp.json()["data"]
        self._token = data["token"]
        self._expires_at = time.time() + data["expiresIn"]
        self._user_id = data["user"]["id"]
        return self._token

    def get_user_id(self) -> int:
        self.get_token()
        return self._user_id

    def invalidate(self):
        """手动使 Token 失效（用于测试过期场景）"""
        self._token = None
        self._expires_at = 0
```

### 5.3 多用户场景

框架应支持同时管理多个用户 Token，用于用户隔离测试：

```python
# fixtures/data_fixtures.py
@pytest.fixture(scope="module")
def user_a(auth_manager):
    return auth_manager.create_account()

@pytest.fixture(scope="module")
def user_b(auth_manager):
    return auth_manager.create_account()

def test_isolation(user_a, user_b):
    """用户 A 无法访问用户 B 的资源"""
```

---

## 6. 测试数据管理

### 6.1 数据工厂模式

```python
# helpers/data_factory.py
import random
import string
from datetime import datetime

class DataFactory:
    """随机测试数据生成器"""

    @staticmethod
    def random_email(prefix: str = "auto") -> str:
        suffix = ''.join(random.choices(string.ascii_lowercase + string.digits, k=8))
        return f"{prefix}_{suffix}@test.com"

    @staticmethod
    def random_phone() -> str:
        return f"1{random.randint(30, 99)}{random.randint(10000000, 99999999)}"

    @staticmethod
    def random_string(length: int = 10) -> str:
        return ''.join(random.choices(string.ascii_letters, k=length))

    @staticmethod
    def random_int(min_val: int = 0, max_val: int = 1000) -> int:
        return random.randint(min_val, max_val)

    @staticmethod
    def uuid() -> str:
        import uuid
        return str(uuid.uuid4())
```

### 6.2 数据准备与清理

```python
# conftest.py
import pytest

@pytest.fixture(scope="module")
def test_product(api_client):
    """创建测试商品，模块结束后清理"""
    # Setup: 创建
    resp = api_client.post("/v1/products", json={...})
    product_id = resp.json()["data"]["id"]
    yield product_id
    # Teardown: 清理
    api_client.delete(f"/v1/products/{product_id}")
```

### 6.3 参数化数据

```python
# tests/functional/test_products.py
import pytest

@pytest.mark.parametrize("page,size,expected_count", [
    (1, 10, 10),    # 正常分页
    (1, 1, 1),      # 单条
    (99999, 10, 0), # 超出范围
])
def test_pagination(page, size, expected_count):
    resp = api_client.get("/v1/products", params={"page": page, "size": size})
    assert len(resp.json()["data"]) == expected_count
```

---

## 7. 断言与校验

### 7.1 分层断言体系

| 层级 | 模块 | 职责 |
|------|------|------|
| HTTP 层 | `http_assertions.py` | 状态码、Header、响应时间 |
| 结构层 | `data_assertions.py` | 字段存在性、类型、嵌套结构 |
| 业务层 | `business_assertions.py` | 业务规则、金额计算、状态机 |
| 数据层 | `db_assertions.py` | 数据库记录一致性 |

### 7.2 断言 API 设计

```python
# assertions/http_assertions.py
class HttpAssertions:
    @staticmethod
    def ok(resp):
        assert resp.status_code == 200, f"预期 200，实际 {resp.status_code}: {resp.text}"

    @staticmethod
    def created(resp):
        assert resp.status_code == 201, f"预期 201，实际 {resp.status_code}: {resp.text}"

    @staticmethod
    def no_content(resp):
        assert resp.status_code == 204, f"预期 204，实际 {resp.status_code}: {resp.text}"

    @staticmethod
    def unauthorized(resp):
        assert resp.status_code in [400, 401], f"预期 4xx，实际 {resp.status_code}"

    @staticmethod
    def forbidden(resp):
        assert resp.status_code == 403, f"预期 403，实际 {resp.status_code}: {resp.text}"

    @staticmethod
    def within_time(resp, max_seconds: float):
        assert resp.elapsed.total_seconds() < max_seconds, \
            f"响应时间 {resp.elapsed.total_seconds():.3f}s 超过 {max_seconds}s"
```

```python
# assertions/data_assertions.py
class DataAssertions:
    @staticmethod
    def has_fields(data: dict, *fields):
        for field in fields:
            assert field in data, f"缺少字段: {field}"

    @staticmethod
    def field_type(data: dict, field: str, expected_type):
        assert isinstance(data[field], expected_type), \
            f"字段 {field} 类型错误: 预期 {expected_type}, 实际 {type(data[field])}"

    @staticmethod
    def field_positive(data: dict, field: str):
        assert data[field] > 0, f"字段 {field} 应大于 0，实际: {data[field]}"
```

### 7.3 软断言支持

```python
# 使用 pytest-check 或自定义实现
from dataclasses import dataclass, field

@dataclass
class SoftAssertions:
    """软断言：收集所有失败，最后统一报告"""
    errors: list = field(default_factory=list)

    def check(self, condition, message=""):
        if not condition:
            self.errors.append(message)

    def assert_all(self):
        if self.errors:
            raise AssertionError("\n".join(self.errors))
```

---

## 8. Schema 校验与契约测试

### 8.1 JSON Schema 校验

对每个接口的响应进行结构校验：

```python
# core/schema_validator.py
import jsonschema

class SchemaValidator:
    """JSON Schema 校验器"""

    def __init__(self, schema_dir: str = "schemas/schemas"):
        self.schema_dir = schema_dir

    def validate(self, response_data: dict, schema_name: str):
        """根据 Schema 文件校验响应数据"""
        import json
        schema_path = f"{self.schema_dir}/{schema_name}.json"
        with open(schema_path) as f:
            schema = json.load(f)
        jsonschema.validate(instance=response_data, schema=schema)
```

**Schema 示例：**
```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "required": ["token", "user", "expiresIn"],
  "properties": {
    "token": {"type": "string", "minLength": 50},
    "expiresIn": {"type": "integer", "minimum": 0},
    "user": {
      "type": "object",
      "required": ["id", "account", "nickname"],
      "properties": {
        "id": {"type": "integer", "minimum": 1},
        "account": {"type": "string", "format": "email"},
        "nickname": {"type": "string"}
      }
    }
  }
}
```

### 8.2 OpenAPI 契约校验

基于 `openapi.yaml` 自动验证请求/响应符合规范：

```python
# 使用 openapi-core 库
from openapi_core import OpenAPI
from openapi_core.validation.request.validators import RequestValidator
from openapi_core.validation.response.validators import ResponseValidator

openapi = OpenAPI.from_file_path("schemas/openapi.yaml")

def validate_response(request, response):
    result = ResponseValidator(openapi).validate(request, response)
    result.raise_for_errors()
```

### 8.3 契约测试（Consumer-Driven Contracts）

对于微服务间调用，使用 Pact 进行契约测试：

```
消费者(前端) ──期望──→ Pact Broker ──验证──→ 提供者(后端)
```

```python
# 使用 pact-python
import pact

@pact.consumer("frontend")
@pact.provider("backend")
def test_product_api(pact):
    pact.given("商品 170 存在")
        .upon_receiving("获取商品详情")
        .with_request("GET", "/v1/products/170")
        .will_respond_with(200, body={
            "id": 170,
            "name": pact.Like("LG 显示器"),
            "price": pact.Like(3299.0)
        })

    with pact:
        result = api_client.get("/v1/products/170")
        assert result.status_code == 200
```

---

## 9. 报告与日志

### 9.1 报告体系

| 报告类型 | 工具 | 用途 |
|----------|------|------|
| **测试报告** | Allure Framework | 用例通过率、失败详情、趋势图、业务分层标签 |
| **覆盖率报告** | pytest-cov | 代码覆盖率（如果可获取后端覆盖） |
| **性能报告** | k6 / Locust 内置 | 响应时间分布、吞吐量 |
| **通知** | 企业微信/钉钉/Slack/邮件 | 测试结果推送 |

### 9.2 Allure 集成 — 请求/响应详情记录

**核心机制**：在 `ApiClient` 的每个 HTTP 方法中，通过 `allure.step` 包裹请求，执行后自动调用 `_attach_request_response()` 将完整的请求体和响应体附加到 Allure 报告中。

**请求信息**（作为 Text 附件）包含：
- 请求方法 (GET/POST/PUT/DELETE)
- 完整 URL（含 Query 参数）
- Request Headers（Authorization 自动脱敏，仅显示前 20 字符）
- Request Body（JSON 格式化，超过 8KB 自动截断）

**响应信息**（作为 Text 附件）包含：
- HTTP 状态码与 Reason
- 响应耗时（秒，精确到毫秒）
- Response Headers
- Response Body（JSON 格式化，超过 8KB 自动截断）

#### 9.2.1 ApiClient 实现

```python
# core/api_client.py — 关键方法
class ApiClient:
    def __init__(self, ..., allure_logging: bool = True):
        # 自动检测 allure 是否可用
        self._allure_logging = allure_logging and _ALLURE_AVAILABLE

    def get(self, path, params=None, **kwargs):
        """GET 请求，自动记录到 Allure"""
        if self._allure_logging:
            with allure.step(f"GET {path}?{params}"):
                resp = self.session.get(...)
                _attach_request_response(resp)  # 附加请求/响应详情
                return resp
        return self.session.get(...)

    # post/put/delete 同理
```

#### 9.2.2 报告钩子（conftest.py）

```python
# conftest.py
def pytest_configure(config):
    """生成 Allure 环境信息"""
    if allure_dir := config.getoption("--alluredir", None):
        # 写入 environment.properties（Python 版本、OS、框架信息）
        # 写入 executor.json（CI 场景可扩展）
```

### 9.3 Allure 业务分层标签体系

框架采用 **Epic → Feature → Story** 三级业务分层标签，结合 `@allure.severity` 和 `@pytest.mark.P0/P1/P2` 标记，在 Allure 报告中形成清晰的业务视图。

#### 9.3.1 标签层级定义

| 标签 | 含义 | 示例 |
|------|------|------|
| `@allure.epic` | 最大业务域/测试阶段 | `电商平台功能测试`、`电商平台端到端测试`、`电商平台冒烟测试` |
| `@allure.feature` | 功能模块 | `用户认证`、`商品管理`、`购物车管理`、`订单管理`、`地址管理`、`完整购物链路`、`用户数据隔离` |
| `@allure.story` | 用户故事/具体接口 | `用户注册`、`用户登录`、`商品列表`、`加入购物车`、`下单结算`、`取消订单` |
| `@allure.title` | 用例标题（可读描述） | `P0: 正向 - 有效账号注册成功` |
| `@allure.severity` | 严重级别 | `BLOCKER` / `CRITICAL` / `NORMAL` |
| `@pytest.mark.P0/P1/P2` | 优先级标记 | 支持 `pytest -m P0` 按优先级筛选执行 |

#### 9.3.2 完整示例

```python
@allure.epic("电商平台功能测试")
@allure.feature("用户认证")
class TestRegister:

    @allure.story("用户注册")
    @allure.title("P0: 正向 - 有效账号注册成功")
    @allure.severity(allure.severity_level.BLOCKER)
    @pytest.mark.P0
    def test_register_valid(self, client):
        ...
```

#### 9.3.3 标签映射关系

```
epic: "电商平台功能测试"
 ├── feature: "用户认证"
 │    ├── story: "用户注册" (P0/P1/P2)
 │    ├── story: "用户登录" (P0/P1/P2)
 │    └── story: "获取当前用户信息" (P1/P2)
 ├── feature: "商品管理"
 │    ├── story: "商品列表" (P0/P2)
 │    ├── story: "商品搜索" (P1/P2)
 │    └── story: "商品详情" (P0/P2)
 ├── feature: "购物车管理"
 │    ├── story: "加入购物车" (P0/P1/P2)
 │    └── story: "删除购物车" (P2)
 ├── feature: "订单管理"
 │    ├── story: "下单结算" (P0/P1/P2)
 │    ├── story: "订单列表" (P0/P2)
 │    ├── story: "订单详情" (P2)
 │    └── story: "取消订单" (P1)
 └── feature: "地址管理"
      ├── story: "添加地址" (P0/P2)
      ├── story: "获取地址列表" (P1)
      └── story: "删除地址" (P2)

epic: "电商平台端到端测试"
 ├── feature: "完整购物链路"
 │    ├── story: "浏览→加购→下单→查单→取消" (P0)
 │    └── story: "商品搜索与筛选" (P1)
 └── feature: "用户数据隔离"
      └── story: "多用户订单隔离" (P1)

epic: "电商平台冒烟测试"
 └── feature: "核心服务可用性"
      ├── story: "健康检查" (BLOCKER)
      ├── story: "用户注册" (BLOCKER)
      ├── story: "用户登录" (BLOCKER)
      ├── story: "商品列表" (CRITICAL)
      ├── story: "加入购物车" (CRITICAL)
      └── story: "下单结算" (CRITICAL)
```

### 9.4 运行与查看报告

```bash
# 运行测试并生成 Allure 原始数据
cd web/api_testcases
pytest tests/ -v --tb=short --alluredir=reports/allure_results

# 生成 HTML 报告
allure generate reports/allure_results -o reports/allure_report --clean

# 通过内置 Web 服务器查看（必须通过 HTTP 访问，直接打开 HTML 会 404）
allure open reports/allure_report --port 8888
# 然后浏览器访问 http://localhost:8888
```

### 9.5 结构化日志

```python
# core/logger.py
import logging
import json
import sys

def setup_logger(name: str = "api_test", level: str = "INFO"):
    logger = logging.getLogger(name)
    logger.setLevel(level)

    handler = logging.StreamHandler(sys.stdout)
    formatter = logging.Formatter(
        '{"time":"%(asctime)s","level":"%(levelname)s","module":"%(name)s","message":"%(message)s"}',
        datefmt="%Y-%m-%dT%H:%M:%S"
    )
    handler.setFormatter(formatter)
    logger.addHandler(handler)
    return logger

logger = setup_logger()
```

### 9.6 报告增强列表

| 能力 | 实现位置 | 说明 |
|------|----------|------|
| 请求/响应详情 | `core/api_client.py` → `_attach_request_response()` | 每个 HTTP 请求自动附加 Body/Headers/Status |
| 环境信息 | `conftest.py` → `pytest_configure()` | 自动生成 `environment.properties` |
| 业务分层标签 | 每个测试类的装饰器 | `@allure.epic` / `@allure.feature` / `@allure.story` |
| 用例标题 | `@allure.title()` | 可读的中文用例描述 |
| 严重级别 | `@allure.severity()` | BLOCKER/CRITICAL/NORMAL 三级映射 |
| 步骤日志 | `ApiClient` 中 `allure.step` | 每个 HTTP 请求作为一个 Step 展示 |
| 优先级标记 | `@pytest.mark.P0/P1/P2` | 支持按优先级筛选运行 |

---

## 10. CI/CD 集成

### 10.1 流水线阶段

```
代码提交 → 代码检查 → 构建 → 冒烟测试 → 功能测试 → 安全测试 → 性能测试 → 部署
```

### 10.2 分级执行策略

```yaml
# .github/workflows/test.yml (GitHub Actions 示例)
name: API Tests

on: [push, pull_request]

jobs:
  smoke:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Run smoke tests
        run: |
          pip install -r requirements.txt
          pytest tests/smoke/ -v --tb=short

  regression:
    needs: smoke
    if: github.ref == 'refs/heads/main' || startsWith(github.ref, 'refs/tags/')
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Run full regression
        run: |
          pip install -r requirements.txt
          pytest -m "not perf and not security" -v --alluredir=reports/allure-results

  security:
    needs: smoke
    if: startsWith(github.ref, 'refs/tags/')
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Run security tests
        run: |
          pip install -r requirements.txt
          pytest tests/security/ -v
```

### 10.3 并行执行

```bash
# 按模块并行
pytest tests/functional/test_auth.py &
pytest tests/functional/test_products.py &
pytest tests/functional/test_cart.py &
wait

# 或使用 pytest-xdist
pytest -n 4  # 4 个 Worker 并行
```

### 10.4 失败重试

```bash
# pytest-rerunfailures: 失败用例自动重试
pytest --reruns 2 --reruns-delay 5
```

---

## 11. Mock 与服务虚拟化

### 11.1 需要 Mock 的场景

| 外部依赖 | Mock 原因 |
|----------|----------|
| 支付网关（支付宝/微信） | 无法在测试环境真实支付，需模拟回调 |
| 短信/邮件服务 | 避免发送真实消息，验证调用参数即可 |
| 第三方物流 API | 轨迹数据模拟 |
| 文件存储（OSS/S3） | 上传下载模拟 |

### 11.2 Mock 实现方案

| 方案 | 适用场景 |
|------|----------|
| **unittest.mock / pytest-mock** | 轻量级，适合 Mock Python 函数 |
| **responses 库** | 拦截 requests 发出的 HTTP 请求 |
| **WireMock / MockServer** | 独立 HTTP Mock 服务，支持录制回放 |
| **自建 Mock 服务** | 复杂场景，如支付全链路模拟 |

```python
# 示例：Mock 支付回调
from unittest.mock import patch

def test_payment_webhook():
    with patch("requests.post") as mock_post:
        mock_post.return_value.status_code = 200
        mock_post.return_value.json.return_value = {"status": "SUCCESS"}
        # 测试支付流程...
```

---

## 12. 性能与压力测试

### 12.1 集成方案

框架应支持与专业压测工具的集成：

| 工具 | 用途 | 集成方式 |
|------|------|----------|
| **k6** | 接口压力测试 | 编写 k6 脚本，从 Python 触发执行 |
| **Locust** | Python 原生压测 | 直接在框架中编写压测用例 |
| **pytest-benchmark** | 单接口性能基准 | 作为 pytest 插件集成 |

### 12.2 性能基准测试

```python
# 使用 pytest-benchmark
def test_product_list_performance(benchmark, api_client):
    """商品列表接口性能基准"""
    result = benchmark(
        lambda: api_client.get("/v1/products", params={"size": 20})
    )
    assert result.status_code == 200
    assert result.elapsed.total_seconds() < 0.5  # p95 < 500ms
```

### 12.3 压测触发

```python
# scripts/run_load_test.py
import subprocess

def run_k6_script(script_name: str, duration: str = "30s"):
    """触发 k6 压测"""
    result = subprocess.run(
        ["k6", "run", f"--duration={duration}", f"k6/{script_name}"],
        capture_output=True, text=True
    )
    # 解析 k6 输出，检查是否通过 SLO
    return result.returncode == 0
```

---

## 13. 安全测试集成

### 13.1 自动化安全扫描

| 测试类型 | 工具/实现 |
|----------|----------|
| SQL 注入 | 自定义 payload 库 + 参数化测试 |
| XSS | 自定义 payload 库 + 存储后验证回显 |
| 鉴权绕过 | 框架级别的 Token 管理 + 鉴权矩阵 |
| 敏感信息泄露 | 响应体扫描（检测 password、token 等字段） |
| HTTPS 强制 | 请求重定向检查 |

### 13.2 安全 Payload 库

```python
# config/payloads/security.yaml
sql_injection:
  - "' OR '1'='1"
  - "'; DROP TABLE users;--"
  - "admin'--"
  - "1 UNION SELECT NULL--"

xss:
  - "<script>alert(1)</script>"
  - "<img src=x onerror=alert(1)>"
  - "javascript:alert(1)"

path_traversal:
  - "../../../etc/passwd"
  - "..%2F..%2F..%2Fetc%2Fpasswd"
```

### 13.3 集成 OWASP ZAP

```python
# 通过 ZAP API 进行自动化安全扫描
# zap-cli quick-scan --self-contained --start-options '-config api.disablekey=true' http://target
```

---

## 14. 数据库与中间件验证

### 14.1 数据库断言

API 测试不限于接口层面，还应验证数据的最终一致性：

```python
# assertions/db_assertions.py
class DbAssertions:
    def __init__(self, db_client):
        self.db = db_client

    def record_exists(self, table: str, **conditions) -> bool:
        query = f"SELECT 1 FROM {table} WHERE " + " AND ".join(f"{k}=%s" for k in conditions)
        return self.db.execute(query, tuple(conditions.values())).fetchone() is not None

    def assert_record_count(self, table: str, expected: int, **conditions):
        # ...
        pass
```

### 14.2 缓存验证

```python
import redis

def test_product_cached_after_first_request(api_client, redis_client):
    """验证商品数据在第一次请求后被缓存"""
    # 确保缓存为空
    redis_client.delete("product:170")
    # 第一次请求
    api_client.get("/v1/products/170")
    # 验证缓存已写入
    assert redis_client.exists("product:170") == 1
```

### 14.3 消息队列验证（如适用）

对于异步流程（如订单创建后发消息），验证消息是否被正确发送/消费。

---

## 15. 实施路线图

### 阶段 1：基础框架（1-2 周）

- [x] 项目目录结构搭建
- [x] `conftest.py` 全局 fixture
- [x] `ApiClient` 核心封装
- [x] `AuthManager` Token 管理
- [x] 配置管理（多环境 YAML）
- [x] 基本断言模块（HTTP + 数据结构）
- [x] 日志配置
- [x] 5-10 条冒烟用例

### 阶段 2：功能完善（2-3 周）

- [ ] `DataFactory` 数据工厂
- [ ] 各模块 `Helper` 类
- [ ] 完整功能测试用例（~80 条）
- [ ] 反向用例全覆盖
- [ ] 鉴权矩阵全覆盖
- [ ] Schema 校验集成
- [ ] Allure 报告集成
- [ ] CI 流水线配置

### 阶段 3：进阶能力（1-2 周）

- [ ] 安全测试 payload 库
- [ ] Mock 服务（支付、短信）
- [ ] 数据库断言模块
- [ ] 缓存/消息队列验证
- [ ] E2E 场景链路覆盖

### 阶段 4：性能与持续优化（持续）

- [ ] 性能基准测试（pytest-benchmark）
- [ ] k6 / Locust 压测脚本
- [ ] 契约测试（Pact）
- [ ] 测试数据自动清理
- [ ] 监控告警接入

---

> **版本历史：**
> - v1.0 (2026-06-28): 初始版本，定义框架完整功能架构