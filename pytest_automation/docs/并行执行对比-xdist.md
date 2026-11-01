# 并行执行方案与基准（pytest-xdist）

> 环境：macOS 12 核 / Python 3.13 / 后端单实例 `localhost:8086` / PostgreSQL 16 + Redis 7（本地容器）。
> 每轮前恢复数据库基线并清理 `login:fail:*`；正式对比采用「分组墙钟」：API 与 UI 两层分别跑串行与并行各若干轮，取中位数。

## 1. 正式口径

| 层 | 串行 | 并行 | 并发粒度 |
|---|---|---|---|
| API | `pytest -m api`（1 进程） | 两阶段 `-n 4 --dist loadgroup` + serial lane | **每个接口独立并发**：同一接口的用例固定在同一 worker 顺序执行，不同接口之间并行 |
| UI | `pytest -m ui`（1 进程） | `-n 3 --dist loadscope` | 每 worker 独立浏览器；会员账号由 `customer` 夹具随机注册，天然隔离 |

方法维度（GET/POST/PUT/DELETE）**不做单独并发运行**，只在报告中以「耗时构成」辅助展示。

## 2. 基准结果

| 层 | 串行 | 并行中位数 | 加速比 | 结果一致性 |
|---|---|---|---|---|
| API（701 条） | **271s**（4分31秒） | **84s**（1分24秒，3 轮：95/80/84） | **3.23x** | 3/3 轮与串行完全一致（605 通过 / 93 失败 / 3 跳过） |
| UI（47 条） | **174s**（2分54秒） | **83s**（1分23秒，2 轮：83/80） | **2.10x** | 2/2 轮 47 条全部通过 |

API 方法耗时构成（辅助口径：单测耗时求和，非墙钟；并行取中位数轮）：

| 方法 | 用例数 | 串行耗时和 | 并行耗时和 | 串行占比 |
|---|---|---|---|---|
| GET | 330 | 7.94s | 8.49s | 55.4% |
| POST | 226 | 4.54s | 5.10s | 31.7% |
| PUT | 83 | 0.70s | 0.73s | 4.9% |
| DELETE | 58 | 1.14s | 1.15s | 8.0% |

> 两列「耗时和」接近是预期：并行缩短的是整轮墙钟，不改变单条用例耗时。

## 3. 实现要点

| 机制 | 说明 |
|---|---|
| 每接口分组 | `conftest` 在收集期为非 serial 用例动态添加 `xdist_group=ep::<主体接口>`，`--dist loadgroup` 保证同接口用例同 worker 顺序执行 |
| 库存分片 | `pick_on_sale_sku` / `pick_sku` 按 `sku_id % worker_count == worker_index` 选 SKU，避免跨 worker 抢同一库存行 |
| serial lane | 15/20 SKU 批量、管理端登录失败类用例打 `@pytest.mark.serial`，第二阶段串行执行（3~5s） |
| UI 隔离 | `logged_in_mall_page` 使用随机会员 token；admin 每 worker 登录一次；截图/trace 按 nodeid 隔离 |
| 产物合并 | 每 worker 的映射/结果按后缀落盘，`merge_parallel_artifacts.py` 合并为与串行同 schema |
| 发布 | `publish_parallel.py` 保留 UI 结果后重建 Allure 与接口视图，契约检查通过 |

## 4. 分组并发的代价

`--dist worksteal`（不保证同接口同 worker）最快可到 66s，但无法满足「每接口独立并发、互不影响」；
`--dist loadgroup` 以 84s（3.23x）换取接口级隔离。若只追求速度、接受同接口跨 worker，可用 `DIST=worksteal`。

## 5. 复现

```bash
# API：串行基准
ADMIN_PASSWORD='***' bash scripts/benchmark_serial_api.sh

# API：两阶段并行（默认 loadgroup）
ADMIN_PASSWORD='***' bash scripts/run_parallel.sh --workers 4

# UI：串行 / 并行
ADMIN_PASSWORD='***' bash scripts/benchmark_ui.sh --mode serial
ADMIN_PASSWORD='***' bash scripts/benchmark_ui.sh --mode parallel --workers 3

# 报告页面：接口自动化 → 并行对比
bash scripts/allure_report.sh
```
