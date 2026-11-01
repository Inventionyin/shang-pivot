# ADR-0004：后端采用 Maven 多模块落地模块化单体

- **状态**：Accepted
- **日期**：2026-09-13
- **决策范围**：跨版本基座（实现 ADR-0001 的工程形态）
- **相关**：`ADR-0001`、`docs/04-architecture/v1.0/接口设计.md`、`docs/开发与运维.md`

## 背景（Context）

ADR-0001 已选定模块化单体 + 限界上下文。仅靠 package 约定无法在编译期阻止 `commerce → identity.entity` 类引用，Review 成本随代码量上升。

## 决策（Decision）

在 `backend/` 下采用 **Maven 多模块**（仍是**单一可部署进程**，不是微服务）：

| 模块 | 类型 | 职责 |
|---|---|---|
| `shang-common` | jar | Result、错误码、traceId、JWT、OSS、安全上下文等 |
| `shang-identity-api` | jar | identity 对外接口/DTO |
| `shang-identity` | jar | RBAC、登录、字典、文件元数据等实现 |
| `shang-commerce-api` | jar | 跨上下文 API（订单查询、库存等） |
| `shang-commerce` | jar | 商品/库存/订单/售后实现；**禁止**依赖 `shang-identity` 实现 |
| `shang-customerservice` | jar | V3.0 空壳；只依赖 `*-api` + common |
| `shang-server` | boot jar | 唯一可执行入口：装配、Flyway、配置 |

### 依赖方向（硬约束）

```text
common
  ↑
identity-api / commerce-api
  ↑
identity / commerce / customerservice
  ↑
shang-server（聚合启动）
```

- 实现模块之间禁止互相依赖；跨上下文只依赖对方 `*-api`。
- 身份透传优先 `common.security.SecurityContexts`。
- 独立网关模块可选；V1 直连 `shang-server`。

### 启动

```bash
cd backend
./mvnw -pl shang-server -am spring-boot:run
```

`.env` / `docker-compose.yml` / `mvnw` 放在 `backend/` 根目录。

## 后果（Consequences）

**正面**：编译期强制边界；抽取服务时沿 `*-api` 换远程调用即可。  
**负面**：父 POM 与 IDE 多模块心智成本略增。  
**回退**：合并单模块会失去编译期边界，不推荐。

## As-built 备注（2026-09）

模块结构已按上表落地；无 `shang-gateway`。新增跨上下文能力时先扩 `*-api`，禁止实现模块互依。
