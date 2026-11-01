#!/usr/bin/env bash
# 本机监听服务器上的告警落盘文件，弹出 macOS 桌面通知（+ 提示音）。
#
# 为什么这样绕：平台跑在服务器上（Linux，没有桌面），Alertmanager 只能把告警写进
# 服务器上的文件；要"叫醒人"就需要本机把那个文件的变化实时取回来。
#   Alertmanager → 服务器 alert-notifier 服务（写 /opt/monitoring/docs/.../alerts.log）
#     → 本脚本用 SSH tail -F 拉回本机 → osascript 弹通知
#
# 用法：
#   bash ./scripts/alert-watch.sh            # 前台运行（Ctrl-C 停止）
#   bash ./scripts/alert-watch.sh --daemon   # 后台运行，日志 /tmp/shang-alert-watch.log
#
# 换真实通知渠道（钉钉/飞书/邮件）请改服务器上的 /opt/monitoring/alertmanager/alertmanager.yml，
# 不需要这个脚本；两者可以并存。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SSH_HOST="${SHANG_SSH_HOST:-111.170.33.2}"
readonly SSH_PORT="${SHANG_SSH_PORT:-28536}"
readonly SSH_KEY="${SHANG_SSH_KEY:-$HOME/.ssh/${SHANG_SSH_KEY_NAME:-projectku_psyidc}}"
readonly REMOTE_LOG="/opt/monitoring/docs/performance-test-records/alerts.log"
readonly LOCAL_MIRROR="$ROOT/records/alerts.log"
readonly LOG=/tmp/shang-alert-watch.log

notify() {  # notify <标题> <内容> <声音>
  local title="$1" body="$2" sound="$3"
  local esc_title esc_body
  esc_title="$(printf '%s' "$title" | sed 's/\\/\\\\/g; s/"/\\"/g')"
  esc_body="$(printf '%s' "$body" | sed 's/\\/\\\\/g; s/"/\\"/g; s/\r//g')"
  osascript -e "display notification \"$esc_title\" with title \"$esc_body\" sound name \"$sound\"" 2>/dev/null || true
}

handle_line() {
  local line="$1"
  [ -n "$line" ] || return 0
  # 服务器侧格式：ts \t state \t severity \t alertname \t summary
  local ts state severity name summary
  IFS=$'\t' read -r ts state severity name summary <<< "$line"
  [ -n "${name:-}" ] || return 0

  mkdir -p "$(dirname "$LOCAL_MIRROR")"
  printf '%s\n' "$line" >> "$LOCAL_MIRROR"

  local icon="🔴"
  local sound="Basso"
  case "${state:-}" in
    resolved) icon="✅"; sound="Glass" ;;
    firing)   icon="🔴"; sound="Basso" ;;
  esac
  printf '[alert] %s %s %s\n' "$state" "$name" "${summary:-}"
  # 标题放告警名，内容放摘要：通知中心里一眼能看清是什么、为什么
  notify "${name}" "${icon} [${severity:-alert}] ${summary:-}" "$sound"
}

run_watch() {
  echo "[alert-watch] 监听 $SSH_HOST:${REMOTE_LOG}（本机镜像 ${LOCAL_MIRROR}）"
  # tail -F：服务器上文件还不存在时等它出现（--retry），删了重建也能跟上
  ssh -i "$SSH_KEY" -p "$SSH_PORT" -o IdentitiesOnly=yes -o BatchMode=yes \
      -o ServerAliveInterval=15 -o ServerAliveCountMax=4 -o LogLevel=ERROR \
      "root@$SSH_HOST" "tail -F -n 0 --retry $REMOTE_LOG 2>/dev/null" |
  while IFS= read -r line; do handle_line "$line"; done
  echo "[alert-watch] 连接断开（服务器链路抖动或文件被轮转），5 秒后重连" >&2
}

if [ "${1:-}" = "--daemon" ]; then
  pkill -f "alert-watch.sh" 2>/dev/null || true
  nohup bash "$0" >"$LOG" 2>&1 &
  sleep 2
  echo "告警监听已在后台运行（日志：${LOG}）；停止：pkill -f alert-watch.sh"
  exit 0
fi

while true; do
  run_watch
  sleep 5
done
