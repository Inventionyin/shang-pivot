#!/usr/bin/env bash
# 尚品汇可观测平台运维入口（在 observability/ 目录下运行，通过 SSH 操作服务器上的平台）。
#
#   bash ./scripts/platform.sh status     采集目标 + 平台服务状态
#   bash ./scripts/platform.sh verify     关键指标是否有数据
#   bash ./scripts/platform.sh alerts     规则 / 活跃告警 / 告警投递记录
#   bash ./scripts/platform.sh traces     追踪：Tempo 里的 span 与 SQL 跨度
#   bash ./scripts/platform.sh logs       日志：索引、管道、来源分布
#   bash ./scripts/platform.sh k6 <脚本> [-e K=V ...]   在服务器上跑 k6（打 127.0.0.1，指标写入本地 Prometheus）
#   bash ./scripts/platform.sh open       打开 UI 隧道（同 open-dashboards.sh）
#   bash ./scripts/platform.sh watch      本机监听告警文件并弹 macOS 通知
#
# 平台本体在服务器 /opt/monitoring（原生 + systemd），服务：prometheus alertmanager grafana
# tempo elasticsearch kibana filebeat node_exporter alert-notifier
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

readonly SSH_HOST="${SHANG_SSH_HOST:-111.170.33.2}"
readonly SSH_PORT="${SHANG_SSH_PORT:-28536}"
readonly SSH_KEY="${SHANG_SSH_KEY:-$HOME/.ssh/${SHANG_SSH_KEY_NAME:-projectku_psyidc}}"
readonly REMOTE_K6="/root/k6/shang"
readonly SSH_OPTS=(-i "$SSH_KEY" -p "$SSH_PORT" -o IdentitiesOnly=yes -o BatchMode=yes
                   -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -o ConnectTimeout=15)
readonly HOST="root@${SSH_HOST}"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
remote() { ssh "${SSH_OPTS[@]}" "$HOST" "$@"; }

# 远端跑一段 python（从 stdin 读脚本，参数跟在后面），避免本地/远端引号互相打架
remote_py() {
  local py="$1"; shift
  ssh "${SSH_OPTS[@]}" "$HOST" "python3 - $*" <<< "$py"
}

require_ssh() { ssh "${SSH_OPTS[@]}" "$HOST" true 2>/dev/null || fail "SSH 连不上 $HOST"; }

cmd_status() {
  require_ssh
  printf '=== 平台服务（服务器 systemd）===\n'
  remote 'for s in prometheus alertmanager grafana tempo elasticsearch kibana filebeat node_exporter alert-notifier; do printf "  %-16s %s\n" "$s" "$(systemctl is-active $s)"; done'
  printf '\n=== 采集目标 ===\n'
  remote "curl -fsS http://127.0.0.1:9090/api/v1/targets?state=active" | python3 -c '
import json, sys
for t in json.load(sys.stdin)["data"]["activeTargets"]:
    print("  %s %-18s %s" % ("✓" if t["health"] == "up" else "✗", t["labels"]["job"], t["scrapeUrl"]))
'
}

cmd_verify() {
  require_ssh
  printf '=== 关键指标（服务器 Prometheus）===\n'
  remote_py '
import json, urllib.parse, urllib.request
def q(expr):
    url = "http://127.0.0.1:9090/api/v1/query?" + urllib.parse.urlencode({"query": expr})
    return json.load(urllib.request.urlopen(url, timeout=10))["data"]["result"]
for expr in ["http_server_requests_seconds_count{job=\"shang-pivot\"}",
             "jvm_memory_used_bytes{job=\"shang-pivot\"}",
             "hikaricp_connections_active{job=\"shang-pivot\"}",
             "tomcat_threads_busy_threads{job=\"shang-pivot\"}",
             "business_failures_total{job=\"shang-pivot\"}",
             "node_memory_MemAvailable_bytes{job=\"shang-pivot-host\"}",
             "rate(traces_spanmetrics_calls_total{service=\"shang-pivot\"}[5m])",
             "k6_http_reqs_total{app=\"shang-pivot\"}"]:
    r = q(expr)
    print("  %-62s %s" % (expr[:62], ("有数据（%d 条序列）" % len(r)) if r else "无数据"))
'
}

cmd_alerts() {
  require_ssh
  printf '=== 规则（尚品汇）===\n'
  remote "curl -fsS http://127.0.0.1:9090/api/v1/rules" | python3 -c '
import json, sys
n = 0
for g in json.load(sys.stdin)["data"]["groups"]:
    mine = [r for r in g["rules"] if r["name"].startswith("尚品汇-")]
    if mine:
        print("  [%s] %d 条" % (g["name"], len(mine)))
        n += len(mine)
print("  合计 %d 条" % n)
'
  printf '\n=== 活跃告警（尚品汇）===\n'
  remote "curl -fsS http://127.0.0.1:9090/api/v1/alerts" | python3 -c '
import json, sys
alerts = [a for a in json.load(sys.stdin)["data"]["alerts"] if a["labels"]["alertname"].startswith("尚品汇-")]
print("  （无）" if not alerts else "")
for a in alerts:
    print("  [%-7s] %s severity=%s" % (a["state"], a["labels"]["alertname"], a["labels"].get("severity")))
'
  printf '\n=== 告警投递记录（服务器 alert-notifier 落盘）===\n'
  remote 'f=$(ls /opt/monitoring/docs/performance-test-records/alerts.log 2>/dev/null); if [ -n "$f" ]; then tail -5 "$f"; else echo "  （还没有告警投递过）"; fi'
}

cmd_traces() {
  require_ssh
  printf '=== Tempo 里尚品汇的 trace（最近 3 条）===\n'
  remote "curl -fsS -G 'http://127.0.0.1:3200/api/search' --data-urlencode 'q={resource.service.name=\"shang-pivot\"}' --data-urlencode 'limit=3'" | python3 -c '
import json, sys
d = json.load(sys.stdin)
tr = d.get("traces") or []
print("  （暂无）" if not tr else "")
for t in tr:
    print("  root=%s  %sms" % (t.get("rootServiceName"), t.get("durationMs")))
'
  printf '\n=== span 指标（含 SQL 跨度）===\n'
  remote_py '
import json, urllib.parse, urllib.request
url = "http://127.0.0.1:9090/api/v1/query?" + urllib.parse.urlencode(
    {"query": "sum by (span_name) (rate(traces_spanmetrics_calls_total{service=\"shang-pivot\"}[5m]))"})
for r in json.load(urllib.request.urlopen(url, timeout=10))["data"]["result"][:10]:
    print("  %-40s %s req/s" % (r["metric"]["span_name"][:40], r["value"][1]))
'
}

cmd_logs() {
  require_ssh
  printf '=== 日志索引 ===\n'
  remote 'curl -fsS "http://127.0.0.1:9200/_cat/indices/shang-pivot-logs-*?h=index,docs.count,store.size&s=index"' | sed 's/^/  /'
  printf '\n=== 解析管道 ===\n'
  remote 'curl -fsS -o /dev/null -w "  shang-pivot-logs 管道 HTTP %{http_code}\n" http://127.0.0.1:9200/_ingest/pipeline/shang-pivot-logs'
  printf '\n=== 来源分布 ===\n'
  remote "curl -fsS -X POST 'http://127.0.0.1:9200/shang-pivot-logs-*/_search' -H 'Content-Type: application/json' -d '{\"size\":0,\"aggs\":{\"by_source\":{\"terms\":{\"field\":\"log_source.keyword\"}},\"by_line\":{\"terms\":{\"field\":\"log_line_type.keyword\"}}}}'" \
    | python3 -c '
import json, sys
a = json.load(sys.stdin).get("aggregations") or {}
for key, title in (("by_source", "来源"), ("by_line", "行类型")):
    buckets = (a.get(key) or {}).get("buckets") or []
    print("  %s: %s" % (title, ", ".join("%s=%d" % (b["key"], b["doc_count"]) for b in buckets) or "（暂无）"))
' 2>/dev/null || echo "  （索引还没建：等有新日志写入）"
  printf '\n=== Filebeat 最近日志 ===\n'
  remote 'tail -3 /opt/monitoring/filebeat/logs/filebeat 2>/dev/null | sed "s/^/  /" || echo "  （无）"'
}

cmd_k6() {
  require_ssh
  [ "$#" -ge 1 ] || fail "用法：platform.sh k6 <脚本> [-e KEY=VALUE ...]"
  local script="$1"; shift
  printf '在服务器上跑 k6：%s（指标写入服务器 Prometheus）\n' "$script"
  # --tag app=shang-pivot：客户端指标带上 app 标签，仪表板据此只显示本项目的压测数据
  remote "cd $REMOTE_K6 && K6_PROMETHEUS_RW_SERVER_URL=http://127.0.0.1:9090/api/v1/write \
    K6_PROMETHEUS_RW_TREND_STATS='p(95),p(99),avg,min,max' \
    /usr/local/bin/k6 run -o experimental-prometheus-rw --tag app=shang-pivot $script $*"
}

case "${1:-}" in
  status) shift; cmd_status "$@" ;;
  verify) shift; cmd_verify "$@" ;;
  alerts) shift; cmd_alerts "$@" ;;
  traces) shift; cmd_traces "$@" ;;
  logs) shift; cmd_logs "$@" ;;
  k6) shift; cmd_k6 "$@" ;;
  open) shift; exec bash "$ROOT/scripts/open-dashboards.sh" "$@" ;;
  watch) shift; exec bash "$ROOT/scripts/alert-watch.sh" "$@" ;;
  *) sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
