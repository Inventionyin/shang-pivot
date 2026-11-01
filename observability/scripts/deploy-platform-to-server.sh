#!/usr/bin/env bash
# 把尚品汇的接入件部署到服务器上的监控平台（在 observability/ 目录下运行）。
#
# 做两件事：
#   1. 上传接入件到服务器 /root/deploy-shang/，并同步 k6 脚本到 /root/k6/shang/
#   2. 在服务器上执行 server-apply-platform.sh（幂等：新增采集任务/规则/仪表板/日志输入/解析管道）
#
#   bash ./scripts/deploy-platform-to-server.sh            # 全量部署（可反复执行）
#   bash ./scripts/deploy-platform-to-server.sh --revert   # 撤掉尚品汇接入项（保留元气购那套）
#   bash ./scripts/deploy-platform-to-server.sh --no-k6    # 跳过 k6 脚本上传
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

readonly SSH_HOST="${SHANG_SSH_HOST:-YOUR_SERVER_IP}"
readonly SSH_PORT="${SHANG_SSH_PORT:-28536}"
readonly SSH_KEY="${SHANG_SSH_KEY:-$HOME/.ssh/${SHANG_SSH_KEY_NAME:-projectku_psyidc}}"
readonly REMOTE_STAGE="/root/deploy-shang"
readonly REMOTE_K6="/root/k6/shang"

readonly SSH_OPTS=(-i "$SSH_KEY" -p "$SSH_PORT" -o IdentitiesOnly=yes -o BatchMode=yes
                   -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -o ConnectTimeout=15)
readonly SCP_OPTS=(-i "$SSH_KEY" -P "$SSH_PORT" -o IdentitiesOnly=yes -o BatchMode=yes -o ConnectTimeout=15)
readonly HOST="root@${SSH_HOST}"

REVERT="no"
K6="yes"
while [ $# -gt 0 ]; do
  case "$1" in
    --revert) REVERT="yes"; shift ;;
    --no-k6) K6="no"; shift ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
log()  { printf '  %s\n' "$*"; }

[ -f "$SSH_KEY" ] || fail "缺少 SSH 私钥 $SSH_KEY"
ssh "${SSH_OPTS[@]}" "$HOST" true 2>/dev/null || fail "SSH 连不上 $HOST"

printf '=== 1. 上传接入件到 %s ===\n' "$REMOTE_STAGE"
ssh "${SSH_OPTS[@]}" "$HOST" "mkdir -p $REMOTE_STAGE/{prometheus,filebeat,grafana,elasticsearch} $REMOTE_K6"
scp "${SCP_OPTS[@]}" prometheus/shang-pivot-scrape.yml     "$HOST:$REMOTE_STAGE/prometheus/" >/dev/null
scp "${SCP_OPTS[@]}" prometheus/rules/shang-pivot-alerts.yml "$HOST:$REMOTE_STAGE/prometheus/" >/dev/null
scp "${SCP_OPTS[@]}" filebeat/shang-pivot-inputs.yml       "$HOST:$REMOTE_STAGE/filebeat/" >/dev/null
scp "${SCP_OPTS[@]}" grafana/dashboards/shang-pivot-perf-overview.json "$HOST:$REMOTE_STAGE/grafana/" >/dev/null
scp "${SCP_OPTS[@]}" elasticsearch/pipeline-shang-pivot-logs.json "$HOST:$REMOTE_STAGE/elasticsearch/" >/dev/null
scp "${SCP_OPTS[@]}" scripts/server-apply-platform.sh      "$HOST:/root/" >/dev/null
log "接入件、应用脚本已上传"

if [ "$K6" = "yes" ]; then
  scp "${SCP_OPTS[@]}" -r k6/. "$HOST:$REMOTE_K6/" >/dev/null
  log "k6 脚本已同步到 ${REMOTE_K6}（在服务器上跑，压 127.0.0.1，客户端指标直接写本地 Prometheus）"
fi

if [ "$REVERT" = "yes" ]; then
  printf '\n=== 2. 回退 ===\n'
  ssh "${SSH_OPTS[@]}" "$HOST" 'bash /root/server-apply-platform.sh --revert'
  exit $?
fi

printf '\n=== 2. 在服务器上应用（幂等）===\n'
ssh "${SSH_OPTS[@]}" "$HOST" "bash /root/server-apply-platform.sh --stage $REMOTE_STAGE"

cat <<EOF

完成。查看入口（本机需开 SSH 隧道，见 scripts/open-dashboards.sh）：
  Grafana         http://localhost:13000/d/shang-pivot-perf-overview
  Kibana          http://localhost:15601   → 数据视图「尚品汇日志」
  平台运维        bash ./scripts/platform.sh status|verify|alerts|traces|logs
EOF
