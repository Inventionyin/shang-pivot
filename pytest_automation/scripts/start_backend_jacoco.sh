#!/usr/bin/env bash
# 以 JaCoCo agent 启动后端（仅本地/夜间使用）：tcpserver 模式，可用 jacococli dump/reset。
#
# 产物：
#   artifacts/jacoco/jacoco.exec          覆盖率数据（agent destfile）
#   .tools/jacoco/*.jar                   agent 与 CLI（自动下载）
#
# 用法：
#   bash scripts/start_backend_jacoco.sh [--build]
#   bash scripts/jacoco_dump.sh            # 单独落盘（会 reset，便于按轮统计）
set -uo pipefail

cd "$(dirname "$0")/.."
REPO="$(cd .. && pwd)"
JACOCO_VERSION="${JACOCO_VERSION:-0.8.12}"
TOOLS="$(pwd)/.tools/jacoco"
AGENT="$TOOLS/org.jacoco.agent-${JACOCO_VERSION}-runtime.jar"
CLI="$TOOLS/org.jacoco.cli-${JACOCO_VERSION}-nodeps.jar"
PORT="${SERVER_PORT:-8086}"
JACOCO_PORT="${JACOCO_PORT:-6300}"
JAR="$REPO/backend/shang-server/target/shang-server-0.0.1-SNAPSHOT.jar"
EXEC_DIR="$(pwd)/artifacts/jacoco"
EXEC="$EXEC_DIR/jacoco.exec"

mkdir -p "$TOOLS" "$EXEC_DIR"

fetch() {
  local url="$1" dest="$2"
  if [ ! -f "$dest" ]; then
    echo "[jacoco] 下载 $(basename "$dest")"
    curl -fsSL "$url" -o "$dest" || { echo "下载失败：$url" >&2; exit 3; }
  fi
}
BASE="https://repo1.maven.org/maven2/org/jacoco"
fetch "$BASE/org.jacoco.agent/${JACOCO_VERSION}/org.jacoco.agent-${JACOCO_VERSION}-runtime.jar" "$AGENT"
fetch "$BASE/org.jacoco.cli/${JACOCO_VERSION}/org.jacoco.cli-${JACOCO_VERSION}-nodeps.jar" "$CLI"

if [ "${1:-}" = "--build" ] || [ ! -f "$JAR" ]; then
  echo "[jacoco] 构建后端 jar"
  (cd "$REPO" && mvn -q -DskipTests -f backend/pom.xml -pl shang-server -am package) || exit 4
fi

if lsof -ti "tcp:$PORT" >/dev/null 2>&1; then
  echo "[jacoco] 停止占用 $PORT 的后端"
  lsof -ti "tcp:$PORT" | xargs kill 2>/dev/null
  for _ in $(seq 1 20); do
    lsof -ti "tcp:$PORT" >/dev/null 2>&1 || break
    sleep 1
  done
fi

mkdir -p "$REPO/backend/logs"
export SERVER_PORT="${SERVER_PORT:-8086}"
export DB_PASSWORD="${DB_PASSWORD:-shang}"
export MINIO_ENDPOINT="${MINIO_ENDPOINT:-http://127.0.0.1:9000}"
export MINIO_ACCESS_KEY="${MINIO_ACCESS_KEY:-minioadmin}"
export MINIO_SECRET_KEY="${MINIO_SECRET_KEY:-minioadmin}"
export MINIO_BUCKET_NAME="${MINIO_BUCKET_NAME:-shang-pivot}"
export JWT_SECRET="${JWT_SECRET:-shang-pivot-ci-jwt-secret-32bytes-minimum!!}"
export RABBITMQ_HOST="${RABBITMQ_HOST:-127.0.0.1}"
export RABBITMQ_PORT="${RABBITMQ_PORT:-5672}"
echo "[jacoco] 启动后端（agent 端口 ${JACOCO_PORT}，exec ${EXEC}）"
cd "$REPO"
nohup java \
  -javaagent:"$AGENT"=destfile="$EXEC",append=false,includes=com.shangpivot.*,output=tcpserver,port="$JACOCO_PORT",address=127.0.0.1 \
  -jar "$JAR" > "$REPO/backend/logs/backend-jacoco.out" 2>&1 &
echo $! > "$EXEC_DIR/backend.pid"

for _ in $(seq 1 120); do
  if curl -fsS "http://127.0.0.1:$PORT/api/home" >/dev/null 2>&1; then
    echo "[jacoco] 后端就绪（pid $(cat "$EXEC_DIR/backend.pid")）"
    exit 0
  fi
  sleep 1
done
echo "[jacoco] 后端启动失败，最后日志：" >&2
tail -50 "$REPO/backend/logs/backend-jacoco.out" >&2
exit 5
