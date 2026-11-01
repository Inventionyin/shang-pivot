# Findings: 尚品汇监控平台

## 1. 服务器侧事实（111.170.33.2，hostname liuliangFdUpRY1gEzUn，SSH 28536，密钥 ~/.ssh/projectku_psyidc 可用）

| 项 | 实测值 |
|---|---|
| 尚品汇服务 | `shang-pivot.service`（systemd，User=shang-pivot，EnvironmentFile=/etc/shang-pivot/shang-pivot.env，`ExecStart=/usr/bin/java -jar /opt/shang-pivot/current/app.jar`，Restart=on-failure） |
| 进程 | PID 已跑 4 天+，**无 javaagent**（对比元气购进程带 `-javaagent:/opt/projectku/otel/opentelemetry-javaagent.jar`） |
| 端口 | 应用仅监听 `127.0.0.1:8086`；nginx 监听 8090/8091（NAT → 60084/60085） |
| **actuator** | **不存在**：`/actuator`、`/actuator/health`、`/actuator/prometheus` 全 404 → 必须加依赖后重新部署才有指标 |
| 日志 | `/opt/shang-pivot/current/logs/`：`shang-pivot.log`（应用）+ `shang-pivot-access.log`（AccessLogFilter），按天滚动，maxHistory 14 |
| 应用日志格式 | `2026-10-08 00:00:00.012 WARN  [scheduling-1] [trace=] [user=-/-] c.s.x.Y - msg` |
| access 日志格式 | `2026-10-08 02:35:20.528 INFO  [http-nio-127.0.0.1-8086-exec-7] [trace=<32hex>] [user=<id>/<phone>] c.s.common.filter.AccessLogFilter - → GET /api/addresses \| ip=… \| ct=-`<br>`… - ← GET /api/addresses \| status=500 \| 8ms \| ip=… \| user=… \| req=- \| resp={"code":50000,…}` |
| node_exporter | **已安装且 active**，仅监听 127.0.0.1:9100 → 直接复用 |
| 回环 4318 | 已被元气购反向隧道（sshd）占用 → 尚品汇用 **4319** |
| 资源 | 16 核 / 16 GB / 28G 磁盘用 8G |
| env 键 | SERVER_ADDRESS=127.0.0.1、SERVER_PORT=8086、DB_URL、REDIS_HOST… （无 LOG_HOME → 默认 logs/ 相对 WorkingDirectory） |

## 2. 尚品汇接口契约（写 k6 用）

| 项 | 值 |
|---|---|
| 成功信封 | `{"code":0,"msg":"success","data":{…},"traceId":"…"}` —— **成功码是 0**（不是 200，与元气购不同） |
| 失败信封 | `{"code":<数值>,"msg":"中文","traceId":"…"}`；系统异常 code=50000 |
| 管理端登录 | `POST /admin/auth/login` `{username,password}` → `data.accessToken` / `data.refreshToken` / `data.user`；超管 admin/Admin@123 |
| C 端登录 | `POST /api/auth/login` `{phone, code, clientType}`（web/h5）→ `data.accessToken` / `data.customer`；演示固定验证码 123456 |
| 免认证 | POST：`/admin/auth/login`、`/admin/auth/refresh`、`/api/auth/**`；GET：`/api/home`、`/api/site/brand`、`/api/products`、`/api/products/**`、`/api/categories`、`/api/search`、`…/files/view` |
| 浏览读 | `GET /api/products?page&size&keyword`、`GET /api/products/{spuId}`（详情含 `data.skus[]`，SKU 有 availableStock/price）、`GET /api/search?q=&page=&size=`、`GET /api/categories`、`GET /api/home` |
| 购物车 | `POST /api/cart/items` `{skuId,quantity}`（Map）；`GET /api/cart` |
| 地址 | `GET /api/addresses` |
| 下单 | `POST /api/orders` SubmitReq `{addressId, cartItemIds[], skuId, quantity, idempotentKey}` |
| 支付 | `POST /api/payment/sandbox` `{orderId}` |
| 种子会员 | 9001/13800000001 … 9005 冻结，密码无关（验证码 123456） |
| 已知线上缺陷（未部署修复） | 下单 500（jsonb/address_snapshot，需 `stringtype=unspecified`）、地址模块 500（V20 补 trace_id）、`/admin/dict-items` 500 —— 部署新 JAR 后才有；写链路压测前要留意 |

## 3. 参考平台（web/）现状与必须改动点

- **本地已有整套 web-* 容器在跑**：ports 9090/9093/3000/3200/4318/9200/5601/9600 全部占用；Docker 总内存 7.748 GiB，已用 ~4.3 GiB。
- web 平台构成：docker-compose（profiles: monitoring / logging）+ prometheus(+rules) + grafana(provisioning+仪表板) + tempo + alertmanager(+alert-sink) + serverbridge(双向 SSH 隧道) + logstash(+logship+Dockerfile) + k6 + scripts/perf-platform.sh + scripts/alert-notifier.py。
- 关键机制：
  - `serverbridge` 一条 ssh 进程两方向：`-R 4318:host.docker.internal:4318`（trace 出站）与 `-L 0.0.0.0:9100:127.0.0.1:9100`（node_exporter 进站），`ExitOnForwardFailure=yes` + 15s 重连。
  - logstash 用 **tcp 输入（5001/5002/5003）**，logship 容器用 `ssh tail -F | nc` 泵日志（exec 输入对低频流不触发，实测 0 事件）。
  - tempo 已放宽 `metrics_generator_ingestion_time_range_slack: 10m`（服务器时钟慢 97s）。
  - k6 以 `-o experimental-prometheus-rw` 写 Prometheus（`--web.enable-remote-write-receiver`）。
  - alert-notifier.py 监听 8899，AppleScript 弹桌面通知；Alertmanager webhook 指向 `host.docker.internal:8899`。
  - Prometheus 只认 `up` + `http_server_requests_seconds_*` + `jvm_*` + `hikaricp_*` + `executor_*` + `process_cpu_usage`（Boot actuator 标准指标）。
- Grafana 仪表板：`projectku-perf-overview.json`（30KB，25 面板），数据源 uid `projectku-prometheus` / `projectku-alertmanager` / `projectku-tempo`。

## 4. 尚品汇后端（backend/）

- Spring Boot **4.1.1**（Boot 4 / Jackson 3 / Micrometer 2），Maven 多模块，启动模块 `shang-server`。
- **pom 无 actuator / micrometer / opentelemetry 依赖**（rg 命中 0）→ 阶段 3 新增。
- logback-spring.xml：LOG_HOME 默认 `logs`，app + access 两个 RollingFileAppender，格式见上。
- TraceIdFilter + TraceIdUtil（shang-common）已在 MDC 写 traceId；响应带 traceId。
- 本地开发依赖：backend/docker-compose.yml（postgres 55432 / redis 56379 / minio），本地 8086 起服务。

## 5. 部署实测（2026-10-08）

### 5.1 服务器 JVM 是 Java 17，不是 pom 写的 21（首次部署失败根因）
- 实测 `java -version` = OpenJDK **17.0.20.1**（`/usr/lib/jvm/java-17-openjdk-amd64`）。
- 已部署的旧包 = class version **61**（Java 17 字节码）；本地用 pom 的 `java.version=21` 构建 = **65**。
- 首次部署因此 `UnsupportedClassVersionError` → 应用每 21 秒重启一次（Restart=on-failure），
  **与 OTel agent 无关**（agent 的 VersionLogger 每次都正常打印 2.32.0）。
- 修法：`mvn -Djava.version=17 clean package`（必须 clean，增量编译不会因 release 变化重编），
  已固化进 `scripts/deploy-app.sh`（`SERVER_JAVA_VERSION` 可覆盖）。
- 回滚机制实测有效：脚本在健康检查失败后恢复旧包、删 drop-in、重启，服务恢复；
  但那次 SSH 长连接被服务器链路掐断（"Operation timed out"），脚本中断在回滚途中，
  人工接着确认状态无碍。健康检查阶段的长连接要容忍断线。

### 5.2 部署成功后的指标事实（/actuator/prometheus，443 行）
- 齐全：`http_server_requests_seconds_{count,sum,max}`、`jvm_*`（57 行，含 memory/gc/threads/classes/buffer）、
  `hikaricp_connections_*`（16 行，max=5）、`executor_*`（taskScheduler / operationLogExecutor）、
  `tomcat_sessions_*`、`process_cpu_usage`、`jdbc_connections_*`、`disk_*`、`application_*`。
- 自定义计数 `business_failures_total{code="40101"}` 实测 +1（一次错误验证码登录）——埋点有效。
- **Tomcat 线程池指标默认没有**（只有 tomcat_sessions_*），需 `server.tomcat.mbeanregistry.enabled=true`；
  已加进 application.yaml。
- `executor_queued_tasks{name="taskScheduler"}` 恒为 1~2（周期任务常驻队列）→
  原来的 `>0` 告警规则实测立刻 pending（假告警），已改为 Tomcat 线程占用比 + 异步池规则（排除 taskScheduler）。
- trace 验证：Tempo 的 `traces_spanmetrics_calls_total` 有数据、`latency_bucket` 45 个序列 → 反向隧道 4319 链路通。

### 5.3 时钟与链路
- 服务器 UTC 比本机**慢 102 秒**（元气购文档记 97 秒；无出网、NTP 不生效）。
  Tempo 已放宽 ingestion slack 到 10m，trace 正常入库；核对时间必须用 `date -u +%s` 两边比。
- Docker 内存：两套平台 + 尚品汇日志栈同时运行实测约 **5.9 GiB / 7.75 GiB**，未 OOM。

## 6. 待验证（下一步）

- k6 预检与浏览压测（部署后写链路应可用：jsonb 修复 + V20 已上线）。
- 服务端 `systemctl status` 的 NRestarts 应为 0（部署后重启计数）。


## 7. 平台迁到服务器（2026-10-08 追加要求后）

### 7.1 服务器上原已存在一套平台（不是本次新建）
`/opt/monitoring/`，原生二进制 + systemd，服务：`prometheus alertmanager grafana tempo
elasticsearch kibana filebeat node_exporter alert-notifier`。
它由 `/root/deploy-platform-on-server.sh` 与 `/root/deploy-elk-on-server.sh` 部署（另一路工作，
本次未改动其既有配置），当前只监控 projectku。所有 UI 只监听 127.0.0.1。

### 7.2 为什么不能像本地那样用 Docker
- `registry-1.docker.io:443`、`docker.elastic.co:443`、`github.com:443`、`cdn.jsdelivr.net:443`
  **全部不可达**（DNS 能解析，TCP 被封）；国内加速器会被新版 dockerd 的鉴权回源校验拒掉。
- 服务器 Docker 已是 29.1.3 + compose 2.40.3，但 `docker system df` 显示零镜像零容器 → 压根没用起来。
- 结论：走原生二进制（Prometheus/Grafana/Tempo/Alertmanager 单文件）+ ELK 官方 tar 包。

### 7.3 接入约定（照服务器既有做法）
| 项 | 约定 |
|---|---|
| Prometheus 采集 | `prometheus.yml` 里加 job（标记块 `# >>> shang-pivot`）；`promtool check config` 校验；`systemctl kill -s HUP prometheus` 热加载 |
| 告警规则 | `rules/shang-pivot-alerts.yml`；**告警名统一带「尚品汇-」前缀**——Alertmanager 按 alertname 分组，重名会与元气购并成一条通知 |
| 仪表板 | 独立目录 `provisioning/dashboards-shang/` + 新增 provider（文件夹「尚品汇可观测」）；**新增 provider 必须重启 Grafana**（配置只在启动读） |
| 日志 | Filebeat `filestream` 输入 + per-input `index`/`pipeline` 覆盖 → 索引 `shang-pivot-logs-*`、管道 `shang-pivot-logs`；`filebeat test config` 校验后重启 |
| ES 管道 | grok + painless（与既有 `projectku-logs` 同风格：grok 抽字段、script 派生 status_class/is_error/is_slow/uri、date 用日志行时间戳）；**上传体就是管道本身**，不能套 `{"名字": {...}}` |
| 告警投递 | Alertmanager → `alert-notifier`(8899) → `/opt/monitoring/docs/performance-test-records/alerts.log`（TSV：ts/state/severity/alertname/summary）；本机 `alert-watch.sh` SSH tail 该文件弹 macOS 通知 |
| 访问 | 全部走 SSH 本地转发（`open-dashboards.sh`），不动服务商 NAT |

### 7.4 指标/追踪标签实测
- span 指标的服务标签是 **`service`**（不是 `service_name`）：`traces_spanmetrics_calls_total{service="shang-pivot"}`。
- k6 的 `--tag app=shang-pivot` 会作为标签写进 Prometheus（`k6_http_reqs_total{app="shang-pivot",...}`），
  仪表板据此把两台应用的压测数据分开。
- 应用侧 OTLP 端点：服务器本机 Tempo `127.0.0.1:4318`（同机部署后不再需要反向隧道 4319）。

### 7.5 内网入口（压测用）
C 端 `http://127.0.0.1:8090/api`、管理端 `http://127.0.0.1:8091/admin`（nginx 反代到 8086）；
服务器上 k6 已装（`/usr/local/bin/k6`），脚本放 `/root/k6/shang/`。
