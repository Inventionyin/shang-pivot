#!/usr/bin/env bash
# 打开到服务器监控平台的 SSH 本地转发（在 observability/ 目录下运行）。
# 平台所有 UI 都只监听服务器回环地址，外部访问一律走这条隧道——不需要服务商开 NAT 端口。
#
#   bash ./scripts/open-dashboards.sh          # 前台保持（Ctrl-C 断开）
#   bash ./scripts/open-dashboards.sh --daemon # 后台运行，日志写 /tmp/shang-observability-tunnel.log
#
# 转发（本机端口 → 服务器 127.0.0.1 端口）：
#   13000 → 3000  Grafana          15601 → 5601  Kibana
#   19090 → 9090  Prometheus       19093 → 9093  Alertmanager
#   19200 → 9200  Elasticsearch    13200 → 3200  Tempo API
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SSH_HOST="${SHANG_SSH_HOST:-111.170.33.2}"
readonly SSH_PORT="${SHANG_SSH_PORT:-28536}"
readonly SSH_KEY="${SHANG_SSH_KEY:-$HOME/.ssh/${SHANG_SSH_KEY_NAME:-projectku_psyidc}}"
readonly LOG=/tmp/shang-observability-tunnel.log

readonly ARGS=(
  -i "$SSH_KEY" -p "$SSH_PORT" -o IdentitiesOnly=yes -o BatchMode=yes
  -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -o ExitOnForwardFailure=yes
  -L 127.0.0.1:13000:127.0.0.1:3000
  -L 127.0.0.1:15601:127.0.0.1:5601
  -L 127.0.0.1:19090:127.0.0.1:9090
  -L 127.0.0.1:19093:127.0.0.1:9093
  -L 127.0.0.1:19200:127.0.0.1:9200
  -L 127.0.0.1:13200:127.0.0.1:3200
  -N "root@${SSH_HOST}"
)

if [ "${1:-}" = "--daemon" ]; then
  pkill -f "L 127.0.0.1:13000:127.0.0.1:3000" 2>/dev/null || true
  nohup ssh "${ARGS[@]}" >"$LOG" 2>&1 &
  sleep 3
  cat <<EOF
隧道已在后台建立（日志：${LOG}）
  Grafana      http://localhost:13000/monitor/grafana/      （账号见本地运维配置）
  Kibana       http://localhost:15601/monitor/kibana/      （数据视图「尚品汇日志」）
  Prometheus   http://localhost:19090
  Alertmanager http://localhost:19093/monitor/alertmanager/
  Elasticsearch http://localhost:19200
  Tempo API    http://localhost:13200
停止：pkill -f 'L 127.0.0.1:13000:127.0.0.1:3000'
EOF
  exit 0
fi

cat <<EOF
建立隧道（Ctrl-C 断开）：
  Grafana http://localhost:13000/monitor/grafana/ | Kibana http://localhost:15601/monitor/kibana/ | Prometheus http://localhost:19090
EOF
exec ssh "${ARGS[@]}"
