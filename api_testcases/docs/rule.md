# API 接口测试用例设计规范（2026-06 初版，已存档）

> ⚠️ 本文件为框架初版方法论，**已被** `docs/测试资料/rule.md`（现行 v2.6，含命名/字段/断言/门禁标准）**取代**，仅作历史存档。


> 适用于 RESTful API 项目的接口自动化测试设计  
> 基于 pytest + requests 框架  
> 最后更新：2026-06-28

---

## 目录

1. [总则](#1-总则)
2. [测试用例分类体系](#2-测试用例分类体系)
3. [正向用例设计方法](#3-正向用例设计方法)
4. [反向用例设计方法](#4-反向用例设计方法)
5. [安全测试用例设计方法](#5-安全测试用例设计方法)
6. [E2E 场景链路设计方法](#6-e2e-场景链路设计方法)
7. [测试数据管理规范](#7-测试数据管理规范)
8. [用例编写规范](#8-用例编写规范)
9. [性能与可靠性测试标准](#9-性能与可靠性测试标准)
附录

---

## 1. 总则

### 1.1 目的

本规范旨在建立一套系统化、可复用的 API 接口测试用例设计方法论，确保测试团队能够：

- **全面覆盖**：对任何 RESTful 接口都能从正向、反向、边界、安全等维度进行系统化用例设计
- **优先级明确**：根据业务影响和风险等级划分用例优先级，指导测试执行策略
- **可自动化**：设计出的用例可直接编码为 pytest 自动化测试脚本
- **可复用**：方法论本身不依赖特定项目，可跨项目复用

### 1.2 适用范围

- RESTful API（GET / POST / PUT / DELETE / PATCH）
- JSON 请求/响应格式
- JWT Bearer Token 或类似 Token 认证机制
- 分页、排序、过滤等常见查询模式

### 1.3 核心原则

| 原则 | 说明 |
|------|------|
| **AAA 模式** | Arrange（准备数据）→ Act（执行操作）→ Assert（验证结果） |
| **一次只测一件事** | 每个测试用例只验证一个明确的行为或场景 |
| **独立性** | 测试用例之间不应有执行顺序依赖，每个用例可独立运行 |
| **可重复性** | 每次运行产生相同结果，不依赖外部状态 |
| **边界优先** | 正向用例覆盖主流程后，优先覆盖边界值场景 |
| **安全必测** | SQL 注入、XSS、未授权访问是每个接口的必测维度 |

### 1.4 优先级定义

| 级别 | 定义 | 典型场景 | 执行频率 |
|------|------|----------|----------|
| **P0** | 核心功能，阻断性缺陷 | 注册、登录、核心业务主流程 | 每次提交 |
| **P1** | 重要功能，高频使用 | 各模块主流程、常见输入组合 | 每日回归 |
| **P2** | 异常/边界/鉴权 | 参数校验、错误码、Token 过期 | 发版前回归 |
| **P3** | 安全/性能/极端场景 | SQL 注入、XSS、大并发 | 大版本前回归 |

---

## 2. 测试用例分类体系

每个接口都应从以下 **6 个维度** 进行用例设计：

```
┌──────────────────────────────────────────────────────┐
│                  接口测试用例维度                       │
├──────────┬──────────┬──────────┬──────────┬──────────┤
│  正向    │  反向    │  边界    │  鉴权    │  安全    │
│ Positive │ Negative │ Boundary │  Auth   │ Security │
├──────────┴──────────┴──────────┴──────────┴──────────┤
│               E2E 场景链路（跨接口）                    │
└──────────────────────────────────────────────────────┘
```

### 2.1 各维度定义

| 维度 | 定义 | 核心问题 |
|------|------|----------|
| **正向 (Positive)** | 使用合法输入，验证接口在正常条件下的预期行为 | "接口能不能正常工作？" |
| **反向 (Negative)** | 使用非法/异常输入，验证接口的错误处理能力 | "输入不对时会怎样？" |
| **边界 (Boundary)** | 使用边界值（最小值、最大值、临界值），验证极限条件下的行为 | "在极限情况下会不会崩？" |
| **鉴权 (Auth)** | 验证不同鉴权状态下的访问控制 | "没权限的人能访问吗？" |
| **安全 (Security)** | 验证接口对常见攻击的防护能力 | "会不会被注入/绕过？" |
| **E2E 链路** | 多接口串联，验证完整业务流程 | "整个流程能跑通吗？" |

---

## 3. 正向用例设计方法

### 3.1 GET 类接口（查询/读取）

#### 3.1.1 基础查询

验证接口在无参数或默认参数下返回预期结果。

**关注点：**
- HTTP 状态码为 200
- 响应体结构完整（字段名、类型、嵌套层级）
- 数据内容正确（与数据源一致）

#### 3.1.2 参数组合查询

验证不同参数组合下的查询结果。

**测试矩阵模板：**

| 参数维度 | 有效值示例 | 验证点 |
|----------|-----------|--------|
| 必填参数 | 一个有效值 | 结果过滤正确 |
| 可选参数（单个） | 有效值 | 不影响其他结果 |
| 多参数组合 | 同时传入多个参数 | 交集/并集逻辑正确 |
| 排序参数 | `sort=field&order=asc/desc` | 排序结果正确 |

#### 3.1.3 分页参数

| 用例 | 参数 | 预期 |
|------|------|------|
| 默认分页 | 不传 page/size | 使用默认值，返回正确条数 |
| 第一页 | page=1, size=10 | 返回 ≤10 条 |
| 中间页 | page=3, size=10 | 返回对应页数据 |
| 最后一页 | page=N, size=10 | 返回剩余条数（可能不足 10 条） |
| 单条 | size=1 | 返回 ≤1 条 |
| 不分页 | size 设为最大值 | 返回 ≤上限条数 |

### 3.2 POST 类接口（创建/提交）

#### 3.2.1 基础创建

验证使用完整合法参数成功创建资源。

**关注点：**
- HTTP 200/201
- 返回新创建资源的标识（ID）
- 资源可通过 GET 接口回查验证

#### 3.2.2 可选字段

**场景矩阵：**

| 用例 | 输入 | 预期 |
|------|------|------|
| 仅必填字段 | 只传 required 字段 | 创建成功，可选字段为默认值 |
| 全部字段 | required + optional 全传 | 创建成功，所有字段值正确 |
| 部分可选字段 | required + 部分 optional | 创建成功，未传的为默认值 |

#### 3.2.3 幂等性（如适用）

对于支持幂等的接口，验证重复请求返回一致结果。

**关注点：**
- 相同 Idempotency-Key 的两次请求返回相同资源 ID
- 资源实际上只创建了一次

### 3.3 PUT/PATCH 类接口（更新）

验证更新操作的各项场景。

**场景矩阵：**

| 用例 | 输入 | 预期 |
|------|------|------|
| 全量更新 | 传所有可修改字段 | 所有字段更新为新值 |
| 部分更新 | 仅传部分字段 | 仅更新传入字段，其余不变 |
| 相同值更新 | 传与当前值相同的值 | 更新成功，数据不变（幂等） |

### 3.4 DELETE 类接口（删除）

验证删除操作的各项场景。

| 用例 | 输入 | 预期 |
|------|------|------|
| 删除存在资源 | 有效 ID | 删除成功，后续 GET 返回不存在 |
| 重复删除 | 再次删除同一 ID | 返回"资源不存在"或错误（幂等） |

---

## 4. 反向用例设计方法

### 4.1 参数缺失

针对每个 **必填字段**，设计缺失该字段的用例。

**模板：**
```
场景：缺少 <字段名>
输入：完整请求体，但去掉 <字段名>
预期：返回参数校验错误（如 VALIDATION_FAILED，HTTP 400）
```

**覆盖策略：** 每个必填字段至少 1 条缺失用例。

### 4.2 参数类型错误

| 错误类型 | 示例 | 预期 |
|----------|------|------|
| 字符串传数字 | 数字字段传 `"abc"` | 类型校验失败 |
| 数字传字符串 | 字符串字段传 `123` | 视实现：解析或拒绝 |
| 布尔传其他 | 布尔字段传 `"yes"` | 类型校验失败 |
| 数组传对象 | 数组字段传 `{}` | 类型校验失败 |
| 对象传数组 | 对象字段传 `[]` | 类型校验失败 |

### 4.3 参数值非法

| 非法类型 | 示例 | 预期 |
|----------|------|------|
| 值为空字符串 | `""` | 视为空值，取决于是否为必填 |
| 值为 null | `null` | 参数校验失败 |
| 值超出范围 | 数量传 `-1`、`0`、超大值 | 参数校验失败 |
| 格式非法 | 邮箱传 `"not-email"`、手机号传 `"abc"` | 格式校验失败 |
| 枚举值非法 | 状态字段传不存在状态 | 参数校验失败 |
| 引用不存在的资源 | ID 字段传不存在的 ID | 返回"资源不存在" |

### 4.4 请求体异常

| 异常场景 | 输入 | 预期 |
|----------|------|------|
| 空请求体 | `{}` | 返回必填字段缺失错误 |
| 非法 JSON | `"{broken json"` | HTTP 400 |
| Content-Type 错误 | 传 `text/plain` 而非 `application/json` | HTTP 415 或解析错误 |
| 请求体过大 | 超大 JSON（>10MB） | HTTP 413 |

### 4.5 业务规则违反

根据具体业务规则设计反向用例：

| 通用场景 | 示例 |
|----------|------|
| 重复操作 | 重复注册、重复创建同名资源 |
| 状态约束 | 在非法状态下执行操作（如取消已完成的订单） |
| 权限约束 | 操作不属于自己的资源 |
| 资源约束 | 在资源不足时执行操作（如库存不足时下单） |

---

## 5. 安全测试用例设计方法

### 5.1 SQL 注入

**适用位置：** 所有接受字符串输入的参数（URL 参数、请求体字段、Header）

| Payload | 用途 | 预期 |
|---------|------|------|
| `' OR '1'='1` | 条件恒真绕过 | 不返回全量数据 |
| `'; DROP TABLE x;--` | 删除表 | 不执行 DROP |
| `admin'--` | 注释绕过 | 鉴权失败 |
| `1 UNION SELECT ...` | UNION 注入 | 不返回额外数据 |

**防护验证：** 所有注入 payload 均不应影响数据库结构或返回越权数据。

### 5.2 XSS（跨站脚本）

**适用位置：** 所有可存储并回显的字符串字段（昵称、内容、描述等）

| Payload | 预期 |
|---------|------|
| `<script>alert(1)</script>` | 存储时转义或拒绝 |
| `<img src=x onerror=alert(1)>` | 存储时转义或拒绝 |
| `<b>bold</b>` | 存储时转义 HTML 标签 |

### 5.3 鉴权绕过

**测试矩阵：**

| 场景 | 预期 |
|------|------|
| 无 Authorization Header | `UNAUTHORIZED` (401) |
| 空 Token（`Bearer `） | `UNAUTHORIZED` |
| 伪造 Token（随机字符串） | `UNAUTHORIZED` |
| 篡改 Token（修改 payload 但签名不对） | `UNAUTHORIZED` |
| 过期 Token | `UNAUTHORIZED` |
| 用户 A 的 Token 操作用户 B 的资源 | `FORBIDDEN` (403) |
| Header 注入（`Bearer token\nX-Injected: true`） | 不产生额外效果 |

### 5.4 参数污染

| 场景 | 方法 | 预期 |
|------|------|------|
| 重复参数 | `?id=1&id=2` | 使用第一个值或报错 |
| 额外未知字段 | `{"extra_field":"value"}` | 忽略未知字段 |
| 批量赋值 | 传不允许修改的字段（如 `isAdmin: true`） | 忽略或拒绝 |

### 5.5 速率限制

| 场景 | 预期 |
|------|------|
| 短时间内大量请求 | 返回 `RATE_LIMITED` (429) |
| 连续错误登录 | 触发账号锁定或延迟响应 |

---

## 6. E2E 场景链路设计方法

### 6.1 链路设计原则

- **覆盖核心业务流程**：选取用户最常用的业务流程
- **多接口串联**：至少 3+ 个接口组成完整链路
- **数据传递**：前一个接口的输出作为后一个接口的输入
- **状态验证**：验证链路中资源状态的一致性

### 6.2 通用链路模板

#### 链路 1：CRUD 完整生命周期

```
创建(CREATE) → 查询详情(READ) → 更新(UPDATE) → 再次查询验证 → 删除(DELETE) → 查询验证已删除
```

**验证点：**
- 创建后数据与输入一致
- 更新后数据确实变更
- 删除后无法再查询到

#### 链路 2：认证 + 鉴权链路

```
注册 → 登录获取 Token → 使用 Token 访问受保护接口 → 使用错误 Token 访问 → 使用其他用户 Token 访问
```

**验证点：**
- Token 有效时可访问
- Token 无效/过期时返回 UNAUTHORIZED
- 跨用户访问返回 FORBIDDEN

#### 链路 3：多用户隔离链路

```
用户A：注册 → 登录 → 创建资源
用户B：注册 → 登录 → 尝试访问用户A的资源
```

**验证点：**
- 用户B无法查看/修改/删除用户A的资源
- 用户B只能操作自己的资源

#### 链路 4：状态流转链路

```
创建资源(状态A) → 操作使状态变为B → 操作使状态变为C → ...
在非法状态执行操作 → 验证错误
```

**验证点：**
- 状态按预期流转
- 非法状态转换被拒绝

### 6.3 链路设计检查清单

- [ ] 是否覆盖了项目中最核心的 3-5 个业务流程？
- [ ] 每个链路是否包含数据在接口间的传递验证？
- [ ] 是否包含了多用户隔离验证？
- [ ] 是否包含了鉴权全链路验证？
- [ ] 是否包含了状态流转的完整路径？

---

## 7. 测试数据管理规范

### 7.1 数据隔离策略

| 策略 | 说明 | 适用场景 |
|------|------|----------|
| **随机账号** | 每次运行生成唯一账号 | 避免与已有数据冲突 |
| **模块级 fixture** | 使用 `scope="module"` 共享登录状态 | 减少重复注册/登录 |
| **数据准备函数** | 编写 Helper 函数封装数据创建 | 提高复用性 |

### 7.2 测试账号创建模板

```python
import random
import string

def create_test_account(base_url: str) -> dict:
    """创建测试账号并返回 (account, password, token, user_id)"""
    prefix = f"auto_{''.join(random.choices(string.ascii_lowercase, k=6))}"
    account = f"{prefix}@test.com"
    password = "Test@123456"

    # 注册
    resp = requests.post(
        f"{base_url}/auth/register",
        json={"account": account, "password": password, "nickname": f"T_{prefix[-4:]}"}
    )
    assert resp.status_code == 200

    # 登录
    resp = requests.post(
        f"{base_url}/auth/login",
        json={"account": account, "password": password}
    )
    data = resp.json()["data"]
    return {
        "account": account,
        "password": password,
        "token": data["token"],
        "user_id": data["user"]["id"],
    }
```

### 7.3 数据依赖管理

```
Fixture 层级（scope=module）：
auth_token
├── 创建地址 (POST /addresses)
│   └── address_id (返回的 ID)
├── 创建资源 (POST /items)
│   └── item_id (返回的 ID)
└── 创建订单 (POST /orders)
    └── order_id (返回的 ID)
```

**设计原则：**
- 使用 pytest fixture 管理共享数据
- fixture 之间通过 yield 返回值传递数据
- 避免在测试用例中硬编码数据 ID

### 7.4 数据清理策略

| 策略 | 说明 |
|------|------|
| **不自动清理** | 保留数据用于问题排查（推荐） |
| **定时清理** | 通过定时任务清理 N 天前的测试数据 |
| **前缀过滤** | 测试数据使用统一前缀（如 `auto_`），便于批量清理 |
| **Cleanup Fixture** | 使用 `yield` + teardown 在测试完成后清理 |

---

## 8. 用例编写规范

### 8.1 命名规范

```
test_<序号>_<模块>_<场景>_<预期>

示例：
  test_01_auth_valid_login           # 正向：有效登录
  test_02_auth_invalid_password       # 反向：错误密码
  test_03_cart_unauth_no_token        # 鉴权：无 Token
  test_04_cart_negative_quantity      # 反向：负数数量
  test_05_prod_boundary_page_zero     # 边界：page=0
  test_06_prod_security_sql_injection # 安全：SQL 注入
```

**命名要素说明：**

| 要素 | 位置 | 可选值 |
|------|------|--------|
| `序号` | test_XX | 两位数序号，如 01, 02 |
| `模块` | 第 3 段 | 业务模块缩写（auth, prod, cart, ord, pay） |
| `场景` | 第 4 段 | valid / invalid / unauth / boundary / security / edge |
| `预期` | 第 5 段 | 描述预期结果的关键词 |

### 8.2 代码模板

```python
#!/usr/bin/env python3
"""
模块名称 API 自动化测试
"""
import os
import pytest
import requests
from typing import Dict

BASE_URL = os.environ.get("BASE_URL", "http://localhost:8080/api")

def _headers(token: str = None) -> Dict[str, str]:
    """构造请求头"""
    h = {"Content-Type": "application/json"}
    if token:
        h["Authorization"] = f"Bearer {token}"
    return h


@pytest.fixture(scope="module")
def auth_token():
    """前置：注册用户并登录，返回 token"""
    # Arrange: 注册 + 登录
    # ...
    yield {"token": result_token, "user_id": result_user_id}


class TestModuleName:
    """某模块测试"""

    def test_01_valid_scenario(self, auth_token):
        """P0: 正向 - 场景描述"""
        # Arrange
        # Act
        resp = requests.get(f"{BASE_URL}/path", headers=_headers(auth_token["token"]))
        # Assert
        assert resp.status_code == 200
        data = resp.json()
        assert data[预期结构断言]

    def test_02_negative_scenario(self, auth_token):
        """P2: 反向 - 场景描述"""
        # Arrange
        # Act
        resp = requests.post(f"{BASE_URL}/path", json={无效数据})
        # Assert
        assert resp.status_code == 400
        assert resp.json()["error"]["code"] == "EXPECTED_ERROR_CODE"

    def test_03_unauthorized(self):
        """P1: 鉴权 - 无 Token 访问"""
        resp = requests.get(f"{BASE_URL}/path")
        assert resp.json()["error"]["code"] == "UNAUTHORIZED"
```

### 8.3 AAA 注释规范

```python
def test_example(self, auth_token):
    # Arrange —— 准备测试数据和前置条件
    resource_id = create_test_resource()

    # Act —— 执行被测操作
    resp = requests.put(f"{BASE_URL}/resource/{resource_id}", json={...})

    # Assert —— 验证结果
    assert resp.status_code == 200
    assert resp.json()["data"]["status"] == "expected_status"
```

### 8.4 断言最佳实践

| 原则 | 说明 | 示例 |
|------|------|------|
| **先断言状态码** | 状态码断言失败时输出响应体辅助排查 | `assert resp.status_code == 200, f"失败: {resp.text}"` |
| **断言关键字段存在** | 用 `in` 检查字段而非直接访问 | `assert "token" in data` |
| **断言字段类型** | 避免 NoneType 异常 | `assert isinstance(data.get("items"), list)` |
| **断言业务值** | 验证具体数值 | `assert data["price"] > 0` |
| **灵活的错误码断言** | 允许实现差异 | `assert error in ["UNAUTHORIZED", "TOKEN_INVALID"]` |

---

## 9. 性能与可靠性测试标准

### 9.1 响应时间基准

| 接口类型 | 参考目标 | 说明 |
|----------|----------|------|
| 缓存命中查询 | p95 < 200ms | 如热点商品查询 |
| 普通查询 | p95 < 500ms | 如列表查询 |
| 简单写入 | p95 < 1s | 如注册、加购 |
| 复杂写入 | p95 < 2s | 如下单、支付 |
| 回调处理 | p95 < 3s | 如支付回调、webhook |

### 9.2 并发测试场景模板

| 场景 | 并发数 | 验证点 |
|------|--------|--------|
| 读并发 | 100-500 并发查询 | 响应时间不显著退化，无 5xx 错误 |
| 写并发-库存 | 50 并发扣减同一库存 | 库存最终正确，无超卖 |
| 写并发-幂等 | 10 并发带相同幂等键 | 只有 1 次生效，其余返回相同结果 |
| 混合负载 | 读写混合 | 系统稳定，无连接池耗尽 |

### 9.3 幂等性测试模板

**需要幂等性保障的接口：**
- 支付类
- 下单类
- 退款/售后类
- 所有带 `Idempotency-Key` 的 POST 接口

**测试方法：**
```python
import uuid

def test_idempotency():
    """验证幂等：相同 Idempotency-Key 发 3 次请求"""
    idempotency_key = str(uuid.uuid4())
    results = []

    for _ in range(3):
        resp = requests.post(
            f"{BASE_URL}/endpoint",
            json={...},
            headers={
                "Content-Type": "application/json",
                "Idempotency-Key": idempotency_key,
                "Authorization": f"Bearer {token}"
            }
        )
        results.append(resp.json()["data"]["id"])

    # 三次请求应返回相同的资源 ID
    assert len(set(results)) == 1
```

---

## 附录

### A. 通用错误码参考

| 错误码 | HTTP | 含义 |
|--------|------|------|
| VALIDATION_FAILED | 400 | 参数校验失败 |
| UNAUTHORIZED | 401 | 未登录或 Token 失效 |
| FORBIDDEN | 403 | 无权限访问 |
| NOT_FOUND | 404 | 资源不存在 |
| CONFLICT | 409 | 资源冲突（重复创建/状态冲突） |
| RATE_LIMITED | 429 | 请求过于频繁 |
| INTERNAL_ERROR | 500 | 服务器内部错误 |

### B. 用例设计检查清单

**单个接口：**
- [ ] 正向用例 ≥ 2 条（基础 + 完整参数）
- [ ] 反向用例 ≥ 3 条（参数缺失 + 类型错误 + 值非法）
- [ ] 边界用例 ≥ 2 条（最小值 + 最大值）
- [ ] 鉴权用例 ≥ 2 条（无 Token + 错误 Token）
- [ ] 安全用例 ≥ 1 条（SQL 注入 或 XSS）

**整体项目：**
- [ ] E2E 链路 ≥ 3 条
- [ ] 多用户隔离验证 ≥ 1 条
- [ ] 幂等性验证 ≥ 2 个接口
- [ ] 并发场景 ≥ 2 个

### C. 测试执行策略

```
快速验证（每次提交）:  P0 + 增量的 P1 用例
每日回归:             P0 + P1 全量
发版前回归:           P0 + P1 + P2
大版本/安全审计:      P0 + P1 + P2 + P3
```

---

> **版本历史：**
> - v1.0 (2026-06-28): 初始版本，建立通用接口测试用例设计规范框架