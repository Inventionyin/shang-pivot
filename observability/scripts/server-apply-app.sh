#!/usr/bin/env bash
# 在服务器上把「可观测性改动」落到尚品汇后端。由 scripts/deploy-app.sh 上传后调用，
# 也可以 ssh 上去单独执行（幂等）。以 root 运行。
#
#   bash server-apply-app.sh --jar /tmp/shang-server-new.jar [--no-tracing]
#   bash server-apply-app.sh --rollback          # 回到上一次部署前的状态
#
# 做的事（顺序即依赖顺序，失败自动回滚）：
#   1) 备份当前 app.jar
#   2) DB_URL 补 stringtype=unspecified —— commerce."order".address_snapshot 是 jsonb 列，
#      缺少该参数时 String 参数按 varchar 绑定，下单必 500（已定位的源码级根因）
#   3) 写 OTel Java Agent 的 systemd drop-in（trace → 服务器本机 Tempo 127.0.0.1:4318）
#   4) 换包 → daemon-reload → restart → 等健康（管理端口 8087 + 应用端口 8086）
#   5) 健康检查失败：恢复备份 jar、撤掉本次新增的 drop-in、重启，退出非零
#
# 采用 systemd drop-in 而不是直接改原 unit：原 unit 保持原样，回退只需删掉 drop-in。
set -euo pipefail

readonly APP_DIR="/opt/shang-pivot/current"
readonly APP_JAR="$APP_DIR/app.jar"
readonly ENV_FILE="/etc/shang-pivot/shang-pivot.env"
readonly SERVICE="shang-pivot"
readonly DROPIN_DIR="/etc/systemd/system/${SERVICE}.service.d"
readonly DROPIN="$DROPIN_DIR/otel.conf"
readonly STATE_DIR="/opt/shang-pivot/.deploy-state"
# 平台现在跑在同一台服务器上（/opt/monitoring/tempo 监听回环 4318），
# 因此 trace 直接推本机 Tempo，不再需要开发机的反向隧道
readonly OTLP_PORT="${OTLP_PORT:-4318}"
readonly OTLP_ENDPOINT="${OTLP_ENDPOINT:-http://127.0.0.1:${OTLP_PORT}}"
readonly APP_PORT="${APP_PORT:-8086}"
readonly MGMT_PORT="${MGMT_PORT:-8087}"
readonly SAMPLING="${OTEL_SAMPLING:-0.1}"
# Agent jar：优先复用元气购已上传的那份（服务器无出网，避免重复上传 20MB）
AGENT_JAR="${OTEL_AGENT_JAR:-}"
if [ -z "$AGENT_JAR" ]; then
  for candidate in /opt/projectku/otel/opentelemetry-javaagent.jar /opt/shang-pivot/otel/opentelemetry-javaagent.jar; do
    [ -f "$candidate" ] && AGENT_JAR="$candidate" && break
  done
fi

NEW_JAR=""
TRACING="yes"
ROLLBACK="no"

while [ $# -gt 0 ]; do
  case "$1" in
    --jar) NEW_JAR="$2"; shift 2 ;;
    --no-tracing) TRACING="no"; shift ;;
    --rollback) ROLLBACK="yes"; shift ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

log() { printf '  %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

wait_healthy() {
  local timeout="${1:-90}" i
  for i in $(seq 1 $((timeout / 2))); do
    sleep 2
    if curl -fsS --max-time 3 "http://127.0.0.1:${MGMT_PORT}/actuator/health" >/dev/null 2>&1 \
       && curl -fsS --max-time 3 "http://127.0.0.1:${APP_PORT}/api/home" 2>/dev/null | grep -q '"code":0'; then
      return 0
    fi
  done
  return 1
}

show_failure_context() {
  systemctl status "$SERVICE" --no-pager -n 5 2>&1 | sed 's/^/    /' || true
  journalctl -u "$SERVICE" -n 25 --no-pager 2>&1 | grep -iE 'error|exception|caused by|failed' | head -10 | sed 's/^/    /' || true
}

# ---------------------------------------------------------------- 回滚
if [ "$ROLLBACK" = "yes" ]; then
  [ -f "$STATE_DIR/previous-jar" ] || die "没有找到上一次部署的备份（$STATE_DIR/previous-jar 不存在）"
  printf '=== 回滚到上一次部署前的 app.jar ===\n'
  install -o shang-pivot -g shang-pivot -m 0644 "$STATE_DIR/previous-jar" "$APP_JAR"
  if [ -f "$STATE_DIR/dropin-added" ]; then
    log "撤掉本次部署新增的 OTel drop-in"
    rm -f "$DROPIN"
    systemctl daemon-reload
  fi
  systemctl restart "$SERVICE"
  if wait_healthy 90; then
    log "已回滚并恢复健康：$(cat "$STATE_DIR/previous-revision" 2>/dev/null || echo 旧包)"
    exit 0
  fi
  show_failure_context
  die "回滚后仍未通过健康检查，需要人工介入"
fi

[ -n "$NEW_JAR" ] || die "用法：server-apply-app.sh --jar <新包路径> [--no-tracing]"
[ -f "$NEW_JAR" ] || die "找不到新包：$NEW_JAR"

printf '=== 0. 前置检查 ===\n'
systemctl is-active --quiet "$SERVICE" || log "警告：$SERVICE 当前不是 running 状态"
command -v awk >/dev/null || die "缺 awk"
[ -f "$ENV_FILE" ] && log "env 文件存在：$ENV_FILE" || die "找不到 $ENV_FILE"

# 反向隧道（trace 出站）没通不算致命：OTel 导出失败不影响业务，只是 trace 进不来。
if ss -lnt 2>/dev/null | grep -q "127.0.0.1:${OTLP_PORT}"; then
  log "OTLP 反向隧道 127.0.0.1:${OTLP_PORT} 已监听"
else
  log "警告：127.0.0.1:${OTLP_PORT} 未监听（服务器上 systemctl status tempo 看一眼）；"
  log "      应用仍可正常启动，只是 trace 暂时送不出来"
fi

printf '\n=== 1. 备份当前版本 ===\n'
mkdir -p "$STATE_DIR"
cp -f "$APP_JAR" "$STATE_DIR/previous-jar"
cat "$APP_DIR/REVISION" > "$STATE_DIR/previous-revision" 2>/dev/null || echo "(未知)" > "$STATE_DIR/previous-revision"
log "已备份到 $STATE_DIR/previous-jar（revision: $(cat "$STATE_DIR/previous-revision")）"

printf '\n=== 2. DB_URL 补齐 stringtype=unspecified ===\n'
if grep -q '^DB_URL=.*stringtype=unspecified' "$ENV_FILE"; then
  log "已包含 stringtype=unspecified，跳过"
else
  cp -f "$ENV_FILE" "$STATE_DIR/previous-env"
  tmp="$(mktemp)"
  value="$(grep -m1 '^DB_URL=' "$ENV_FILE" | cut -d= -f2-)"
  [ -n "$value" ] || die "env 文件里没有 DB_URL"
  case "$value" in
    *\?*) new_value="${value}&stringtype=unspecified" ;;
    *)    new_value="${value}?stringtype=unspecified" ;;
  esac
  awk -v new="$new_value" '
    !done && /^DB_URL=/ { print "DB_URL=" new; done=1; next }
    { print }
  ' "$ENV_FILE" > "$tmp" && mv "$tmp" "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  log "已追加参数（原文件备份在 $STATE_DIR/previous-env）"
fi

printf '\n=== 3. OTel Java Agent（systemd drop-in）===\n'
if [ "$TRACING" = "yes" ]; then
  [ -n "$AGENT_JAR" ] && [ -f "$AGENT_JAR" ] || die "找不到 opentelemetry-javaagent.jar（可设 OTEL_AGENT_JAR=/path/to.jar）"
  # Pyroscope agent 只影响持续剖析，缺失时 trace 仍要能工作，所以按存在与否决定是否挂载
  PYROSCOPE_AGENT_JAR="${PYROSCOPE_AGENT_JAR:-/opt/monitoring/pyroscope/pyroscope-agent.jar}"
  PYROSCOPE_AGENT_ARG=""
  [ -f "$PYROSCOPE_AGENT_JAR" ] && PYROSCOPE_AGENT_ARG="-javaagent:${PYROSCOPE_AGENT_JAR} "
  mkdir -p "$DROPIN_DIR"
  [ -f "$DROPIN" ] || touch "$STATE_DIR/dropin-added"
  cat > "$DROPIN" <<CONF
# 由 observability/scripts/server-apply-app.sh 生成：给尚品汇后端挂 OpenTelemetry Java Agent。
# 回退：rm -f $DROPIN && systemctl daemon-reload && systemctl restart $SERVICE
[Service]
Environment=OTEL_SERVICE_NAME=shang-pivot
# trace 推给服务器回环 ${OTLP_PORT}，由同机的 Tempo（/opt/monitoring/tempo）接收
Environment=OTEL_EXPORTER_OTLP_ENDPOINT=${OTLP_ENDPOINT}
Environment=OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
Environment=OTEL_TRACES_EXPORTER=otlp
# 指标已由 actuator 暴露、日志已由文件+Logstash 采集，关掉 OTel 这两路导出避免重复上报
Environment=OTEL_METRICS_EXPORTER=none
Environment=OTEL_LOGS_EXPORTER=none
Environment=OTEL_RESOURCE_ATTRIBUTES=deployment.environment=perf,service.namespace=shang-pivot
# 采样率 0.1：压测时全量采样会压垮 Tempo；定位单个请求时临时改成 1.0 再重启
Environment=OTEL_TRACES_SAMPLER=parentbased_traceidratio
Environment=OTEL_TRACES_SAMPLER_ARG=${SAMPLING}
# 方法级追踪：agent 默认只在 HTTP/JDBC 等边界生成 span，看不到业务函数。
# 这里显式列出关键 Controller/Service 方法，链路变成
#   GET /api/products → StorefrontController.products → CatalogReadService.productPage → SELECT commerce.spu
# 语法（agent methods 模块）：包.类[方法1,方法2];包.类2[方法]，方法与类名需写全，不支持通配符。
Environment=OTEL_INSTRUMENTATION_METHODS_INCLUDE=com.shangpivot.commerce.controller.StorefrontController[home,products,productDetail,search,cart,addCart,submit,sandboxPay];com.shangpivot.commerce.application.CatalogReadService[home,categories,productPage,search,detail,toCard];com.shangpivot.commerce.application.CartService[getCart,addItem];com.shangpivot.commerce.application.OrderSubmitService[submit]

# --- Span Profiles：Pyroscope 持续剖析（从慢 span 直达火焰图）---
# 扩展 jar 由 grafana/otel-profiling-java 提供，经 OTel Agent 的 extensions 机制加载；
# 它把当前 span 的 id 注入 profile 标签（span_id / trace_id），Grafana 的 Tempo 数据源
# 配了 tracesToProfiles 后即可在 trace 视图里看到该 span 的火焰图。
Environment=OTEL_JAVAAGENT_EXTENSIONS=/opt/monitoring/pyroscope/pyroscope-otel-javaagent-extension.jar
Environment=PYROSCOPE_APPLICATION_NAME=shang-pivot
Environment=PYROSCOPE_SERVER_ADDRESS=http://127.0.0.1:4040
Environment=PYROSCOPE_FORMAT=jfr
Environment=PYROSCOPE_PROFILING_INTERVAL=10ms
Environment=PYROSCOPE_PROFILER_EVENT=itimer
Environment=PYROSCOPE_PROFILER_LOCK=10ms
Environment=PYROSCOPE_PROFILER_ALLOC=512k
Environment=PYROSCOPE_UPLOAD_INTERVAL=15s
Environment=OTEL_PYROSCOPE_START_PROFILING=true
Environment=OTEL_PYROSCOPE_ADD_PROFILE_URL=false
Environment=OTEL_PYROSCOPE_ADD_PROFILE_BASELINE_URL=false

# systemd 不允许向 ExecStart 追加参数：先清空继承值，再整行重写
ExecStart=
ExecStart=/usr/bin/java -javaagent:${AGENT_JAR} ${PYROSCOPE_AGENT_ARG}-jar ${APP_JAR}
CONF
  log "已写入 ${DROPIN}（agent: ${AGENT_JAR}，采样率 ${SAMPLING}）"
else
  log "跳过（--no-tracing）"
fi

# 日志目录兜底：logs/ 是 WorkingDirectory 下的相对路径，任何一方部署时若以 root 建目录，
# 应用（User=shang-pivot）会因 Permission denied 启动失败（2026-10-19 实际踩过）。
mkdir -p "$DROPIN_DIR"
cat > "$DROPIN_DIR/logging-dir.conf" <<'CONF'
# 启动前修正日志目录属主（"+" = 以 root 执行；服务本身 User=shang-pivot）
# 回退：rm -f 本文件 && systemctl daemon-reload
[Service]
ExecStartPre=+/bin/mkdir -p /opt/shang-pivot/current/logs
ExecStartPre=+/bin/chown -R shang-pivot:shang-pivot /opt/shang-pivot/current/logs
CONF
log "已写入 ${DROPIN_DIR}/logging-dir.conf（日志目录兜底）"

printf '\n=== 4. 换包并重启 ===\n'
install -o shang-pivot -g shang-pivot -m 0644 "$NEW_JAR" "$APP_JAR"
log "新包已就位：$(stat -c '%s bytes  %y' "$APP_JAR" 2>/dev/null)"
# 修正版本标识：原 REVISION 是随旧发布包带过来的 git 短哈希，换包后必须同步，
# 否则线上"看着是旧版本"（排查时极易误判）
printf 'local-build:%s deployed:%s\n' \
  "$(md5sum "$APP_JAR" | cut -d' ' -f1)" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$APP_DIR/REVISION"
chown shang-pivot:shang-pivot "$APP_DIR/REVISION"
log "REVISION 已更新：$(cat "$APP_DIR/REVISION")"
systemctl daemon-reload
systemctl restart "$SERVICE"

printf '\n=== 5. 等待健康检查 ===\n'
if wait_healthy 120; then
  log "服务已就绪（管理端口 ${MGMT_PORT} + 应用端口 ${APP_PORT} 均通过）"
else
  printf '\n健康检查未通过，自动回滚：\n' >&2
  show_failure_context
  install -o shang-pivot -g shang-pivot -m 0644 "$STATE_DIR/previous-jar" "$APP_JAR"
  [ -f "$STATE_DIR/dropin-added" ] && { rm -f "$DROPIN"; log "已撤掉本次新增的 drop-in"; }
  systemctl daemon-reload
  systemctl restart "$SERVICE"
  if wait_healthy 90; then
    printf '\n已回滚为部署前的包，服务恢复。请查看上面的启动报错后重试。\n' >&2
  else
    printf '\n回滚后仍不健康，需要人工介入（SSH 上去看 journalctl -u %s）。\n' "$SERVICE" >&2
  fi
  exit 1
fi

printf '\n=== 6. 确认指标端点与 agent ===\n'
# 只取一次端点再本地比对：逐条 curl 在应用刚启动时容易假阴性（实测踩过）
METRICS_FILE="$(mktemp)"
if curl -fsS --max-time 20 --retry 3 --retry-delay 2 "http://127.0.0.1:${MGMT_PORT}/actuator/prometheus" -o "$METRICS_FILE" 2>/dev/null; then
  log "指标端点行数：$(wc -l < "$METRICS_FILE")"
  for m in http_server_requests_seconds_count jvm_memory_used_bytes hikaricp_connections_active tomcat_threads_busy_threads; do
    grep -q "^${m}" "$METRICS_FILE" && log "指标存在：$m" || log "指标缺失：$m"
  done
else
  log "警告：读不到 /actuator/prometheus（检查 management 端口配置）"
fi
rm -f "$METRICS_FILE"

if [ "$TRACING" = "yes" ]; then
  # 以进程命令行判断是否挂上 agent（比翻日志可靠：启动日志行数多，容易被截断）
  if ps -eo cmd | grep -q "[j]avaagent:.*opentelemetry"; then
    log "OTel agent 已挂载（进程命令行含 javaagent）"
  else
    log "警告：进程命令行里没有 javaagent，agent 未生效"
  fi
fi

printf '\n完成。回滚命令：bash server-apply-app.sh --rollback\n'
