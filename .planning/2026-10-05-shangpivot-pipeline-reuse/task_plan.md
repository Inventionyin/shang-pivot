# Task Plan: 把元气购测试流水线复用到尚品汇（shang-pivot）

## Goal
把元气购已验证的流水线「接口/需求文档 → 用例清单 → lint 清零 → 全量上传禅道」完整复用到尚品汇。
按阶段执行，每阶段有验收门，门未过不得进入下一阶段；决策点必须问用户。

## Next Step
决策点 1/2/3 待用户答复；阶段 1 领域词典可在等待期间先做（不依赖决策）。

## Current Phase
Phase 0（准备）——复制完成，验收门被决策点 1/2 阻塞

## Phases

### Phase 0: 准备
- [x] 读 rule.md / lint方案.md / api_testcases/README.md 并摘录约束
- [x] 核实事实基线（模板源、目标仓库、错误码、迁移、禅道可达性）
- [x] 复制 `web/api_testcases/` → `shang-pivot-master/api_testcases/`（排除元气购运行产物）
- [x] 复制治理文档 → `shang-pivot-master/docs/测试资料/`
- [ ] 【决策点 1】禅道「尚品汇」产品是否存在 / 新建 → product_id
- [ ] 【决策点 2】管理端测试账号 + C 端登录方式
- [ ] 验收门：目录就位 + product_id 明确 + 管理端登录接口验证通过
- **Status:** in_progress（被决策点阻塞）

### Phase 1: 文档三件套
- [ ] 领域词典.md 新建（ErrorCode.java 全量错误码 + Flyway/源码状态枚举 + 「机器可读词表：」行）
- [ ] 接口文档升级（springdoc OpenAPI 导出 + 源码逐 Controller 核对 → docs/测试资料/接口文档.md）
- [ ] 源码 → 文档差异表清零（135 个操作 100% 有记录）
- [ ] 【决策点 3】需求编号风格（PRD-xx 保留改 lint / 统一 XX-01 改文档）
- **Status:** pending

### Phase 2: 用例生成
- [ ] 适配 generate_cases.py（免认证白名单按 /admin/**、/api/** 实际鉴权配置；标题、OpenAPI 路径）
- [ ] 适配 case_lint.py 的项目常量（R13 /v1 前缀、R14 catalog、R23 /v1/admin、AUTH_EXEMPT、BODYLESS_ACTIONS）
- [ ] 重写 merge_business_cases.py 为尚品汇业务链路（BUS-<域>-NNN）
- [ ] 走查 + 抽样 10 条核对
- **Status:** pending

### Phase 3: lint 清零
- [ ] 离线 lint fail 0（留存报告）
- [ ] lint_selftest.py 全过
- **Status:** pending

### Phase 4: 试点 → 全量上传
- [ ] 试点 3-5 条（C 端读 / 管理端 / 含体写 各≥1）
- [ ] 线上抽查步骤三件套
- [ ] 全量上传 100% 成功
- [ ] `case_lint.py --online` 复核
- **Status:** pending

### Phase 5: 交付汇总
- [ ] 《尚品汇流水线复用报告》→ docs/测试资料/
- [ ] 【决策点 4】本轮范围止点确认
- **Status:** pending

## Decisions Made
| Decision | Rationale |
|---|---|
| 不复制 `api_testcases/artifacts/`（18MB 元气购产物） | 内含 `zentao_case_manifest.json`，会被 upload-cases 当作"已上传"而跳过，是功能性隐患 |
| 不复制 ProjectKu 的 接口文档.md / 需求文档.md / 领域词典.md | 三者是元气购内容；lint 会按 `docs/测试资料/接口文档.md` 做 R14 接口清单比对、按 `需求文档.md` 做 R28 追溯，放错内容会导致全库 warn |
| 治理流程文档（rule/lint方案/评审方案/验收标准治理手册/标准-规则追溯矩阵）原样复制 | 与项目无关的通用规范 |
| 契约源用本地 `127.0.0.1:8086/v3/api-docs` 导出的 OpenAPI | 线上 60084/60085 未暴露 /v3/api-docs（返回 SPA index.html）；本地运行代码与目标仓库 src 逐模块 diff 为 0 |

## Open Questions
- 真值层级冲突：本次任务书要求「源码 > OpenAPI > 接口文档 > PRD」，而 rule.md §2 是「接口文档/OpenAPI > 源码」。执行按任务书，但需在报告中标注该差异。
