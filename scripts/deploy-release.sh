#!/usr/bin/env bash
# 尚品汇整包发布：后端 JAR + 管理端/M 端静态文件 → 服务器新 release → 切 current → 重启 → 验收。
# 在仓库根目录运行。失败会自动把 current 指回上一个 release 并重启。
#
#   bash scripts/deploy-release.sh --jar backend/shang-server/target/shang-server-0.0.1-SNAPSHOT.jar \
#        --mall-dist shang-mall/dist --admin-dist shang-admin-web/dist --revision repo-e62193c
#
#   bash scripts/deploy-release.sh --rollback         # 回滚到上一个 release
#
# 约束（实测踩过的坑，脚本已固化）：
#   - 服务器 JVM 是 Temurin 21.0.13（/opt/jdk21，见 deploy 记录）：按项目默认 Java 21 构建即可
#   - logs/ 目录必须属 shang-pivot，否则应用启动即 Permission denied（systemd 有兜底，脚本也会 chown）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SSH_HOST="${SHANG_SSH_HOST:-YOUR_SERVER_IP}"
readonly SSH_PORT="${SHANG_SSH_PORT:-28536}"
readonly SSH_KEY="${SHANG_SSH_KEY:-$HOME/.ssh/${SHANG_SSH_KEY_NAME:-projectku_psyidc}}"
readonly BASE="/opt/shang-pivot"
readonly SVC="shang-pivot"
readonly SSH_OPTS=(-i "$SSH_KEY" -p "$SSH_PORT" -o IdentitiesOnly=yes -o BatchMode=yes
                   -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -o ConnectTimeout=15)
readonly SCP_OPTS=(-i "$SSH_KEY" -P "$SSH_PORT" -o IdentitiesOnly=yes -o BatchMode=yes -o ConnectTimeout=15)
readonly HOST="root@${SSH_HOST}"

JAR=""; MALL=""; ADMIN=""; REVISION=""; ROLLBACK="no"
while [ $# -gt 0 ]; do
  case "$1" in
    --jar) JAR="$2"; shift 2 ;;
    --mall-dist) MALL="$2"; shift 2 ;;
    --admin-dist) ADMIN="$2"; shift 2 ;;
    --revision) REVISION="$2"; shift 2 ;;
    --rollback) ROLLBACK="yes"; shift ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

log() { printf '  %s\n' "$*"; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
remote() { ssh "${SSH_OPTS[@]}" "$HOST" "$@"; }

ssh "${SSH_OPTS[@]}" "$HOST" true 2>/dev/null || fail "SSH 连不上 $HOST"

if [ "$ROLLBACK" = "yes" ]; then
  printf '=== 回滚到上一个 release ===\n'
  remote "set -e
    prev=\$(readlink -f $BASE/.deploy-state/previous-release 2>/dev/null || true)
    [ -n \"\$prev\" ] && [ -d \"\$prev\" ] || { echo '没有找到上一个 release'; exit 1; }
    ln -sfn \"\$prev\" $BASE/current
    systemctl restart $SVC
    echo \"  current -> \$prev\""
  exit $?
fi

[ -n "$JAR" ] && [ -f "$JAR" ] || fail "缺少 --jar 或文件不存在"
[ -n "$MALL" ] && [ -d "$MALL" ] || fail "缺少 --mall-dist 或目录不存在"
[ -n "$ADMIN" ] && [ -d "$ADMIN" ] || fail "缺少 --admin-dist 或目录不存在"
[ -z "$REVISION" ] && REVISION="deploy-$(date +%Y%m%d-%H%M)"

WORK="$(mktemp -d)"
readonly WORK
trap 'rm -rf "$WORK"' EXIT

printf '=== 1. 组装发布包（%s）===\n' "$REVISION"
mkdir -p "$WORK/$REVISION"
install -m 0644 "$JAR" "$WORK/$REVISION/app.jar"
cp -a "$MALL" "$WORK/$REVISION/mall"
cp -a "$ADMIN" "$WORK/$REVISION/admin"
printf '%s\n' "$REVISION" > "$WORK/$REVISION/REVISION"
{
  echo "release: $REVISION"
  echo "built_at: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "jar_md5: $(md5 -q "$JAR" 2>/dev/null || md5sum "$JAR" | awk '{print $1}')"
  echo "jar_size: $(stat -f%z "$JAR" 2>/dev/null || stat -c%s "$JAR")"
  echo "source: ${REVISION}"
} > "$WORK/$REVISION/MANIFEST.txt"
TAR="$WORK/release.tar.gz"
tar czf "$TAR" -C "$WORK" "$REVISION"
log "包大小：$(du -h "$TAR" | awk '{print $1}')"

printf '\n=== 2. 上传并校验 ===\n'
LOCAL_MD5="$(md5 -q "$TAR" 2>/dev/null || md5sum "$TAR" | awk '{print $1}')"
scp "${SCP_OPTS[@]}" "$TAR" "$HOST:/tmp/shang-pivot-release.tar.gz" >/dev/null
REMOTE_MD5="$(remote "md5sum /tmp/shang-pivot-release.tar.gz | awk '{print \$1}'")"
[ "$LOCAL_MD5" = "$REMOTE_MD5" ] || fail "md5 不一致（本地 $LOCAL_MD5 / 远端 $REMOTE_MD5）"
log "md5 一致：$LOCAL_MD5"

printf '\n=== 3. 服务器侧：解包 → 切换 → 重启 → 验收 ===\n'
# 用「引号 heredoc + 位置参数」：heredoc 里的 $(...) 必须全部在服务器上执行，
# 不加引号会被本地 shell 展开（实测踩过：汇报行全是空值）。
remote "bash -s -- '$BASE' '$REVISION' '$SVC'" <<'REMOTE'
set -euo pipefail
BASE="$1"; REV="$2"; SVC="$3"
mkdir -p "$BASE/releases" "$BASE/.deploy-state"
prev="$(readlink -f "$BASE/current" 2>/dev/null || true)"
[ -n "$prev" ] && [ -d "$prev" ] && echo "$prev" > "$BASE/.deploy-state/previous-release"
tar xzf /tmp/shang-pivot-release.tar.gz -C "$BASE/releases" 2>/dev/null
chown -R shang-pivot:shang-pivot "$BASE/releases/$REV"
mkdir -p "$BASE/releases/$REV/logs"
chown -R shang-pivot:shang-pivot "$BASE/releases/$REV/logs"
chmod 755 "$BASE/releases/$REV/logs"
ln -sfn "$BASE/releases/$REV" "$BASE/current"
echo "  current -> $BASE/releases/$REV"
systemctl restart "$SVC"

ok=no
for i in $(seq 1 60); do
  sleep 2
  if curl -fsS -m 3 http://127.0.0.1:8087/actuator/health >/dev/null 2>&1 \
     && curl -fsS -m 3 http://127.0.0.1:8086/api/home 2>/dev/null | grep -q '"code":0'; then ok=yes; break; fi
done
if [ "$ok" != "yes" ]; then
  echo "  健康检查未通过，回滚到上一个 release" >&2
  journalctl -u "$SVC" -n 15 --no-pager 2>&1 | grep -iE "error|exception|caused by" | head -5 | sed 's/^/    /' >&2 || true
  if [ -n "$prev" ]; then ln -sfn "$prev" "$BASE/current"; systemctl restart "$SVC"; fi
  exit 1
fi
echo "  服务已就绪：$(systemctl is-active "$SVC")（重启计数 $(systemctl show "$SVC" -p NRestarts --value)）"
echo "  版本标识：$(cat "$BASE/current/REVISION")"
echo "  端口监听数：$(ss -lnt | grep -cE ':8086|:8087')"
curl -s -o /dev/null -w "  内网 /api/home -> %{http_code}\n" -m 8 http://127.0.0.1:8090/api/home
curl -s -o /dev/null -w "  管理端首页 -> %{http_code}\n" -m 8 http://127.0.0.1:8091/
REMOTE

cat <<EOF

发布完成。本机复查：
  curl -s -o /dev/null -w '%{http_code}\\n' http://${SSH_HOST}:60084/api/home
  curl -s -o /dev/null -w '%{http_code}\\n' http://${SSH_HOST}:60085/
回滚：bash scripts/deploy-release.sh --rollback
EOF
