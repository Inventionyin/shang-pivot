# Task Plan: 尚品汇监控平台（指标 / 日志 / 追踪 / 告警）

## Goal
给尚品汇建一套指标 / 日志 / 追踪 / 告警平台。**最终形态：平台部署在被测服务器上**
（111.170.33.2 的 `/opt/monitoring/`，原生二进制 + systemd），与服务器上原有的元气购平台共用
Prometheus / Grafana / Tempo / ELK，按 job、规则文件、仪表板、索引隔离尚品汇的数据。
开发机只负责开 SSH 隧道看 UI 与转告警桌面通知。
（中途曾用 Docker 在本机实现过一版并验证通过，按"平台必须在服务器上"的要求已删除。）

## Next Step
全部阶段完成。遗留：告警阈值仍待容量压测标定（规则里标注为经验值）；如需对外直接访问 UI，
要服务商加 NAT 端口（当前一律走 SSH 隧道）。

## Current Phase
已完成（阶段 1–6 全部通过，平台现驻服务器）

## 关键约束（不可违反）
1. **端口必须与正在运行的 web-* 平台错开**（9090/9093/3000/3200/4318/9200/5601/9600 已被占），否则两套平台只能跑一套。
2. **服务器 127.0.0.1:4318 已被元气购反向隧道占用** → 尚品汇 OTLP 反向隧道用 4319。
3. **生产变更（换 JAR / 重启 shang-pivot.service）不在本任务自动执行**：本地验证到位后交由用户拍板，脚本备好。
4. 本地 Docker 仅 7.7 GiB，已用 ~4.3 GiB → 日志栈起来前后要盯内存。

## Phases

### Phase 1: 指标 + 告警 + 追踪栈（observability/ 骨架 + monitoring profile）  ✅ 完成
- [ ] 目录骨架 + docker-compose.yml（服务同名、宿主端口错开）
- [ ] prometheus.yml + rules（适配尚品汇 job/uri/端口 8087）
- [ ] tempo.yml / alertmanager.yml / alert-sink / serverbridge（远程 4319）
- [ ] grafana provisioning + 仪表板（uid/title/实例名改尚品汇）
- [ ] scripts/perf-platform.sh 适配 + alert-notifier.py（18899）
- [x] 验收门：**3 个采集目标全 up**、数据源 3 个、仪表板 25 面板、规则 13 条、主机指标有数
- **Status:** done（应用目标在部署 actuator 后转 up）

### Phase 2: 日志栈（ELK + logship 拉尚品汇日志）  ✅ 完成
- [ ] logstash 管道适配 shang-pivot 两种日志格式（app + access，含 trace/status/ms）
- [ ] logship 改拉 /opt/shang-pivot/current/logs/ 两个文件（去掉 nginx 源）
- [ ] Kibana 数据视图 shang-pivot-logs
- [x] 验收门：`shang-pivot-logs-*` 有文档且持续增长；access 行字段（status/duration/uri/trace/user/direction）解析完整；
      app 行与堆栈续行分类正确；两套平台共存内存 5.9 GiB / 7.75 GiB
- [x] 修复实测缺陷：logship 的 `nc` 缺 `-N` → ssh 断线后泵卡死（日志静默 12 分钟），已修并验证续传
- **Status:** done

### Phase 3: 后端 actuator 接入（指标源）  ✅ 完成并已部署
- [ ] spring-boot-starter-actuator + micrometer-registry-prometheus（Boot 4.1.1）
- [ ] management 端口 8087 仅回环 + 暴露 health/prometheus
- [x] `spring-boot-starter-actuator` + `micrometer-registry-prometheus`；management 8087 仅回环
- [x] `business_failures_total{code}` 埋点（GlobalExceptionHandler.handleBiz）
- [x] `server.tomcat.mbeanregistry.enabled=true`（否则 tomcat_threads_* 不存在）
- [x] 服务器部署完成（用户追加要求「部署到服务器上」）：新包 + DB_URL 补 stringtype + OTel Agent drop-in，健康检查通过
- [x] 实测：/actuator/prometheus 443 行；http/jvm/hikari/executor/tomcat/business 指标齐；NRestarts=0
- **Status:** done（踩坑与修法见 findings.md §5）

### Phase 4: k6 压测脚本适配尚品汇  ✅ 完成
- [ ] lib/shang.js（信封 code=0、admin 登录 username/password、C 端 phone+code+clientType）
- [ ] preflight / browse-load / login-load / checkout-smoke
- [x] 预检实测：写链路「可压测」（地址 20 条、SKU 池 20 个、管理端读通）
- [x] 浏览压测 VUS=3/30s：279 请求 0 失败，552 断言全过，p95 137.8ms；k6_* 指标进 Prometheus
- [x] 写链路 VUS=1/12s：45 请求 0 失败，下单+沙箱支付全通过（9 单）
- [x] 修实测缺陷：雪花 ID `Number()` 丢精度导致「收货地址无效」，改字符串传
- **Status:** done

### Phase 5: 服务器侧启用 + 文档 + 交付验收  ✅ 完成
- [x] `scripts/deploy-app.sh` + `scripts/server-apply-app.sh`（备份/DB_URL/Agent/重启/健康检查/自动回滚）
- [x] `observability/README.md`、`observability/k6/README.md`、`docs/可观测平台.md`，并在 `docs/开发与运维.md` 加了指路
- [x] 全量验收：3 目标 up、trace 含 SQL span、告警闭环（桌面通知实测到 firing/resolved）、日志增长
- **Status:** done

## Decisions Made
| Decision | Rationale |
|---|---|
| 平台放 `shang-pivot-master/observability/`，自包含一套 | 用户要求"在尚品汇中创建"；与 web/ 平台互不依赖 |
| 宿主端口整体错开（19090/19093/13000/13200/14318/19200/15601/19600/18899/18086/19100） | web-* 平台正在运行且占用原端口；错开后两套可并存 |
| 复用服务器已有 node_exporter(9100) 与 otel agent jar | 服务器无出网，避免重复下载上传；两者都是同主机共享资源 |
| 日志源用应用自己的 app+access 日志，不用 nginx | 尚品汇后端 AccessLogFilter 已含 trace/status/耗时/用户，比 nginx combined 更有信息量，且不动服务器 nginx |
| 指标走 actuator 独立管理端口 8087（仅回环） | 不暴露公网、不走 nginx；SSH 正向隧道直达 |
| 生产重启/换 JAR 不自动执行 | AGENTS.md：生产变更需当前任务明确授权；且新 JAR 含未部署的 P0 修复，属用户决策 |

### Phase 6: 平台迁到服务器（用户追加要求）  ✅ 完成
- [x] 服务器事实核查：Docker 已装但**出网被封**（registry/elasticsearch/github 全不可达）→ 走原生二进制 + systemd
- [x] 复用服务器上已有平台（`/opt/monitoring/`，systemd：prometheus/alertmanager/grafana/tempo/elk/filebeat/node_exporter/alert-notifier）
- [x] 接入件：两个采集 job、13 条规则（「尚品汇-」前缀）、仪表板（独立文件夹）、ES 管道、Filebeat 输入、Alertmanager 抑制规则
- [x] 服务器侧脚本 `server-apply-platform.sh`（幂等、标记块、promtool/filebeat 校验、失败恢复）+ 本机 `deploy-platform-to-server.sh`
- [x] 运维脚本 `platform.sh`（status/verify/alerts/traces/logs/k6）、隧道 `open-dashboards.sh`、告警弹窗 `alert-watch.sh`
- [x] 应用侧 OTLP 改指服务器本机 Tempo(4318)，重启验证 span（含 SQL 跨度）入库
- [x] k6 上传服务器并在服务器上跑通（客户端指标带 `app="shang-pivot"`）
- [x] 删除本地 Docker 拓扑（compose/serverbridge/logstash/tempo/alertmanager/perf-platform.sh）
- [x] 验收：5 个采集目标全 up、13 条规则、25 面板仪表板、日志 568 条、trace 与 SQL 跨度、告警落盘 + 本机弹窗
- **Status:** done

## Open Questions
- 告警阈值标定：尚品汇尚未做容量压测，规则里的阈值是经验值（已在规则文件里标注），首次压测后回填。
- 是否把 k6 也传到服务器本机跑（避免公网波动影响压测结果）；脚本已按 127.0.0.1:80 兼容。
