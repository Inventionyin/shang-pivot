#!/usr/bin/env bash
# 部署接入可观测性的后端包到服务器（在 observability/ 目录下运行）。
#
# 一条命令做完：
#   本机构建 → 上传（md5 校验）→ 服务器侧备份/补 DB_URL/挂 OTel Agent/重启/等健康
#   健康检查失败会在服务器上自动回滚（见 scripts/server-apply-app.sh）
#
#   bash ./scripts/deploy-app.sh                # 全流程（含 trace Agent）
#   bash ./scripts/deploy-app.sh --no-tracing   # 只换包，不动 Agent
#   bash ./scripts/deploy-app.sh --skip-build   # 复用 target/ 里已构建的 jar
#   bash ./scripts/deploy-app.sh --rollback     # 只回滚到上一次部署前
#
# 注意：这个包同时带来两件事——① actuator 指标端点（可观测平台的数据源）；
# ② 此前已修但未部署的缺陷修复（V20 迁移、下单 jsonb、空参校验等）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_DIR="$(cd "$ROOT/../backend" && pwd)"
readonly ROOT BACKEND_DIR
readonly JAR="$BACKEND_DIR/shang-server/target/shang-server-0.0.1-SNAPSHOT.jar"

readonly SSH_HOST="${SHANG_SSH_HOST:-111.170.33.2}"
readonly SSH_PORT="${SHANG_SSH_PORT:-28536}"
readonly SSH_KEY="${SHANG_SSH_KEY:-$HOME/.ssh/${SHANG_SSH_KEY_NAME:-projectku_psyidc}}"
readonly REMOTE_TMP="/tmp/shang-server-new.jar"

readonly SSH_OPTS=(-i "$SSH_KEY" -p "$SSH_PORT" -o IdentitiesOnly=yes -o BatchMode=yes
                   -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -o ConnectTimeout=15)
readonly SCP_OPTS=(-i "$SSH_KEY" -P "$SSH_PORT" -o IdentitiesOnly=yes -o BatchMode=yes -o ConnectTimeout=15)
readonly HOST="root@${SSH_HOST}"

BUILD="yes"
TRACING="yes"
ROLLBACK="no"

while [ $# -gt 0 ]; do
  case "$1" in
    --skip-build) BUILD="no"; shift ;;
    --no-tracing) TRACING="no"; shift ;;
    --rollback) ROLLBACK="yes"; shift ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
log() { printf '  %s\n' "$*"; }

[ -f "$SSH_KEY" ] || fail "缺少 SSH 私钥 $SSH_KEY"
ssh "${SSH_OPTS[@]}" "$HOST" true 2>/dev/null || fail "SSH 连不上 $HOST"

printf '=== 0. 上传服务器侧脚本 ===\n'
scp "${SCP_OPTS[@]}" "$ROOT/scripts/server-apply-app.sh" "$HOST:/root/server-apply-app.sh" >/dev/null
log "已上传 /root/server-apply-app.sh"

if [ "$ROLLBACK" = "yes" ]; then
  printf '\n=== 回滚 ===\n'
  ssh "${SSH_OPTS[@]}" "$HOST" 'bash /root/server-apply-app.sh --rollback'
  exit $?
fi

if [ "$BUILD" = "yes" ]; then
  printf '\n=== 1. 构建后端（跳过测试）===\n'
  if [ -z "${JAVA_HOME:-}" ]; then
    if [ -x /usr/libexec/java_home ]; then
      JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    fi
  fi
  export JAVA_HOME
  [ -n "${JAVA_HOME:-}" ] || fail "未找到 JDK 21，请设置 JAVA_HOME"
  log "JAVA_HOME=$JAVA_HOME"

  # 优先项目自带的 mvnw；某些受限环境（沙箱/权限）无法执行它，回退到系统 mvn；再不行用 MVN_CMD 指定
  if [ -n "${MVN_CMD:-}" ]; then
    MVN="$MVN_CMD"
  elif [ -x "$BACKEND_DIR/mvnw" ] && "$BACKEND_DIR/mvnw" -v >/dev/null 2>&1; then
    MVN="$BACKEND_DIR/mvnw"
  else
    MVN="mvn"
  fi
  log "使用 $MVN"
  # 服务器 JVM 是 Java 17（/usr/lib/jvm/java-17-openjdk-amd64），而 pom 的 java.version=21：
  # 直接构建产出 class 65 的包，部署后必然 UnsupportedClassVersionError（已实测踩过）。
  # 因此按服务器 JVM 版本编译（源码本身兼容 17）。服务器若装了 JDK 21，设 SERVER_JAVA_VERSION=21。
  # 必须带 clean：增量编译会跳过未变更的类，release 改不生效。
  BUILD_RELEASE="${SERVER_JAVA_VERSION:-17}"
  log "编译目标 Java ${BUILD_RELEASE}（与服务器 JVM 对齐）"
  (cd "$BACKEND_DIR" && "$MVN" -B -q -DskipTests -Djava.version="$BUILD_RELEASE" -pl shang-server -am clean package)
  log "构建完成"
else
  printf '\n=== 1. 跳过构建（--skip-build）===\n'
fi

[ -f "$JAR" ] || fail "找不到构建产物 $JAR"

printf '\n=== 2. 上传新包并校验 md5 ===\n'
LOCAL_MD5="$(md5 -q "$JAR" 2>/dev/null || md5sum "$JAR" | awk '{print $1}')"
scp "${SCP_OPTS[@]}" "$JAR" "$HOST:$REMOTE_TMP" >/dev/null
REMOTE_MD5="$(ssh "${SSH_OPTS[@]}" "$HOST" "md5sum $REMOTE_TMP | awk '{print \$1}'")"
[ "$LOCAL_MD5" = "$REMOTE_MD5" ] || fail "md5 不一致（本地 $LOCAL_MD5 / 远端 ${REMOTE_MD5}），重传后再试"
log "md5 一致：${LOCAL_MD5}（$(du -h "$JAR" | awk '{print $1}')）"

printf '\n=== 3. 服务器侧应用（备份 / DB_URL / Agent / 重启 / 健康检查）===\n'
TRACING_FLAG=""
[ "$TRACING" = "no" ] && TRACING_FLAG="--no-tracing"
ssh "${SSH_OPTS[@]}" "$HOST" "bash /root/server-apply-app.sh --jar $REMOTE_TMP $TRACING_FLAG"

printf '\n=== 4. 本机侧复查（经 SSH 隧道以外的公网入口）===\n'
sleep 2
code="$(curl -s -o /dev/null -w '%{http_code}' -m 10 "http://${SSH_HOST}:60084/api/home" || echo 000)"
log "公网入口 $SSH_HOST:60084/api/home -> HTTP $code"
[ "$code" = "200" ] || printf '  警告：公网入口未返回 200，请人工确认（内网健康检查已通过）\n'

cat <<EOF

完成。后续：
  1) 等 10~20 秒让 Prometheus 抓一轮，再看平台：
     bash ./scripts/perf-platform.sh status     # 采集目标应为 3 个 up
     bash ./scripts/perf-platform.sh verify     # 关键指标应有数据
  2) trace：Grafana(13000) → Explore → Tempo，或 bash ./scripts/perf-platform.sh traces
  3) 出问题回滚：bash ./scripts/deploy-app.sh --rollback
EOF
