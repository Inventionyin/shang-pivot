# Progress Log — 尚品汇可观测平台

## Session: 2026-10-08

### 最终形态（一句话）
平台的**本体跑在服务器上**（`/opt/monitoring/`，原生二进制 + systemd），与服务器上原有的元气购平台
共用 Prometheus / Grafana / Tempo / ELK；尚品汇以"加法"接入（独立 job、规则文件、仪表板、日志索引）。
本机只开 SSH 隧道看 UI + 转告警桌面通知。四块能力均已实测通过。

> 中途曾按"平台在开发机"的思路用 Docker 做了一版并验证通过；用户明确要求"部署到服务器上"后，
> 改为在服务器上落地，本机那套 Docker 拓扑已停用并从仓库删除。

### 交付物
| 位置 | 内容 |
|---|---|
| `observability/prometheus/` | 采集任务块（`shang-pivot` → 127.0.0.1:8087、`shang-pivot-host` → 127.0.0.1:9100）+ 13 条告警规则（「尚品汇-」前缀） |
| `observability/grafana/dashboards/` | 25 面板仪表板（数据源用服务器现有 uid；追踪面板按 `service="shang-pivot"`、k6 面板按 `app="shang-pivot"`、告警面板按 `alertname=~"尚品汇-.*"` 过滤） |
| `observability/elasticsearch/` | ES 解析管道 `shang-pivot-logs`（grok + painless，app/access 两种格式） |
| `observability/filebeat/` | 两个日志输入（per-input index/pipeline 覆盖 → `shang-pivot-logs-*`） |
| `observability/scripts/` | `deploy-platform-to-server.sh`（上传+应用+`--revert`）、`server-apply-platform.sh`（服务器侧幂等安装）、`platform.sh`（status/verify/alerts/traces/logs/k6）、`open-dashboards.sh`（SSH 隧道）、`alert-watch.sh`（告警弹窗）、`deploy-app.sh` + `server-apply-app.sh`（应用侧发布，失败自动回滚） |
| `observability/k6/` | 共享库 + 4 个脚本（在服务器上跑，压 127.0.0.1:8090/8091，指标写服务器 Prometheus） |
| `docs/可观测平台.md` | 架构、动过哪里、回退、端口、10 条实测坑 |
| `backend/` 代码 | actuator+micrometer 依赖、management 8087、Tomcat MBean、`business_failures_total` 埋点 |

### 逐项验收（实测证据）
| 能力 | 证据 |
|---|---|
| 指标 | 采集目标 5 个全 up（含元气购两个，原配置未被破坏）；http/jvm/hikari/tomcat/business 指标有数据；仪表板在 Grafana「尚品汇可观测」文件夹 |
| 日志 | 索引 `shang-pivot-logs-2026.10.08`：568 条（access 528 / app 40）；access 行解析出 direction/uri/status/duration/status_class/is_error/client_ip；BizException 行解析出 WARN + 干净 message |
| 追踪 | Tempo 收到 `rootServiceName=shang-pivot` 的 trace；span 指标含 SQL 跨度（`SELECT commerce.sku`、`UPDATE commerce.browse_history` 等） |
| 告警 | 13 条规则加载；实测「尚品汇-服务不可用」firing→resolved 与注入的「尚品汇-链路自检」都落到服务器 `alerts.log`，并由本机 `alert-watch.sh` 弹出通知 |
| 压测 | 服务器上预检 14 项全 PASS（写链路可压测）；browse 207 请求 0 失败（p95 119ms）；k6 指标带 `app="shang-pivot"` 进 Prometheus |

### 本轮引入并修复的缺陷（交付前自查）
1. ES 管道上传体多套了一层名字包装 → 400 `[processors] required property is missing`；改为管道体本身；
2. Grafana 新增 provider 不热加载（provisioning 配置只在启动时读）→ 补"文件有变化才重启 Grafana"；
3. `$VAR（` 被吞进变量名（新脚本 6 处）→ 全仓扫描并自动修，警告写进踩坑清单；
4. `readonly STAGE` 与 `--stage` 参数冲突 → 取消 readonly；
5. 追踪面板误用 `service_name` 标签（实际是 `service`）→ 按实际标签过滤。

### 环境事实（结论性）
- 服务器 **Docker 已装但出网被封**（registry-1.docker.io / docker.elastic.co / github 均不可达），
  国内加速器会被新版 dockerd 的鉴权回源校验拒掉 → 平台走原生二进制 + systemd。
- 服务器 JVM 是 **Java 17**（pom 写 21）→ 构建必须 `-Djava.version=17 clean package`（脚本已固化）。
- 服务器时钟比真实时间慢约 **100 秒**：同机部署下 span 不会被 Tempo 丢弃，但看板按"最近 1 分钟"
  查询看不到刚产生的数据（面板统一用 5 分钟窗口）。
- 开发机原先那套 Docker 平台已停并从仓库删除，本机内存从 5.9 GiB 降到 1.1 GiB；
  另一路（元气购）留下的 `web-serverbridge-1` 是**访问隧道**，保留未动。

### 遗留 / 建议
1. 告警阈值仍为经验值，首次容量压测后按实测曲线回填。
2. 若不依赖 SSH 隧道直接从浏览器访问 Grafana/Kibana，需要服务商加 NAT 端口映射
   （服务器公网只映射了 60080/60082/60084/60085/28536 等少数端口）。
3. k6 写链路压测会真实创建订单并消耗 SKU 库存（本轮在演示环境创建过 9 单），
   大批量压测前先确认库存与回退方式。

## 追加（2026-10-08 晚）：公网入口单层认证

- 服务器 60080 的 `/monitor/grafana/` 原先是"nginx basic + Grafana 登录"两层；按用户要求改成单层：
  Grafana 加 drop-in `grafana.service.d/auth-proxy.conf`（`[auth.proxy]` 信任 `X-WEBAUTH-USER`，
  白名单 127.0.0.1，`auto_assign_org_role=Editor`），nginx 侧加一行 `proxy_set_header X-WEBAUTH-USER $remote_user;`。
- 实测：只带 nginx 口令即可拿到仪表板页面（200）与接口（25 面板），代理用户 monitor 为 Editor；
  不带口令仍 401（一层门在）；本机直连 3000 仍走 Grafana 登录页（admin/admin）以便管理。
- 回退：删 drop-in + daemon-reload + 重启 grafana；nginx 用 `projectku-monitoring.conf.bak-singleauth` 还原。
- 顺带确认：Prometheus 页面提示服务器与浏览器时间差 1m45s（与既有结论一致，同机部署不影响链路）。
