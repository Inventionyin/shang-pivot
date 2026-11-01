# 运行时覆盖率（JaCoCo）操作说明

静态链路覆盖率回答“用例有没有碰到这个接口/这条链路”，JaCoCo 行覆盖率回答“这次运行真正执行了后端哪些代码”。
两者互补：PR 阶段看静态矩阵，夜间回归跑一次运行时覆盖率。

## 前置

- 后端 jar 与源码同一次构建（`backend/shang-server/target/shang-server-0.0.1-SNAPSHOT.jar`）；
- 本机 JDK 21、可访问 Maven Central（首次自动下载 JaCoCo 0.8.12）；
- 后端由脚本启动（不能用已在跑的旧进程）。

## 一轮完整流程

```bash
cd pytest_automation

# 1) 构建并以 agent 启动；tcpserver 模式，端口 6300
bash scripts/start_backend_jacoco.sh --build

# 2) 跑 API 套件（或 nightly）
TEST_ENV=dev ADMIN_PASSWORD='Admin@123' \
  .venv/bin/python -m pytest -m api -q --alluredir=artifacts/allure --clean-alluredir

# 3) 从 agent 拉取 exec 并 reset，避免下一轮累加
bash scripts/jacoco_dump.sh --reset

# 4) 生成 HTML/CSV/XML + 摘要，并写入报告覆盖率页
.venv/bin/python scripts/jacoco_report.py

# 5) 重新生成报告（build_coverage_matrix 会合并 artifacts/jacoco/summary.json）
bash scripts/allure_report.sh
```

产物：

| 路径 | 内容 |
|---|---|
| `artifacts/jacoco/jacoco.exec` | 探针原始数据 |
| `artifacts/jacoco/jacoco.csv` | 类级行/分支/指令覆盖 |
| `artifacts/jacoco/html/index.html` | JaCoCo 官方 HTML |
| `artifacts/jacoco/summary.json` | 总行覆盖率 + 按包汇总 |
| `ext/data/coverage.json#runtime` | 报告「覆盖率」页展示的数据 |

## 口径与限制

- `includes=com.shangpivot.*`，只统计业务代码，排除框架与第三方包；
- 覆盖率来自整个测试运行，不是单个接口；报告按包展示，接口级行覆盖需要按接口分段执行
  （`jacoco_dump.sh --reset` 可在自建脚本里逐组调用，但需串行、成本高，默认不在夜间跑）；
- 重新编译后端会使 class id 与 exec 不匹配，必须固定同一次构建；
- agent 停止后 exec 不再增长；`--reset` 会清空服务端数据，落盘文件保留。

## 常见问题

- `dump` 连接失败：确认后端是以 `start_backend_jacoco.sh` 启动的，6300 未被占用；
- 覆盖率异常低：确认测试请求打到该进程（`SHANG_API_BASE_URL=http://127.0.0.1:8086`），
  且运行前没有执行 `jacoco_dump.sh --reset` 清掉数据；
- 报告页没有运行时卡片：`artifacts/jacoco/summary.json` 不存在，或 `build_coverage_matrix.py`
  在 `jacoco_report.py` 之前执行——重跑第 4、5 步即可。
