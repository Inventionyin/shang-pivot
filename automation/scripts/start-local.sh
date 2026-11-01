#!/usr/bin/env bash
# 启动本地被测环境：PostgreSQL/Redis + 后端 + 商城 + 管理端。幂等，已在运行的服务不会重复启动。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG_DIR="$ROOT/automation/artifacts/logs"
mkdir -p "$LOG_DIR"

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}"
MVN="${MVN:-$(command -v mvn || true)}"
[ -n "$MVN" ] || MVN="/Users/imac/.m2/wrapper/dists/apache-maven-3.9.12/57e1dc81/bin/mvn"

wait_http() {
  local url="$1" name="$2" limit="${3:-90}"
  for _ in $(seq 1 "$limit"); do
    if curl -fsS --max-time 2 "$url" >/dev/null 2>&1; then
      printf '  ✓ %s 就绪\n' "$name"
      return 0
    fi
    sleep 1
  done
  printf '  ✗ %s 未就绪：%s\n' "$name" "$url" >&2
  return 1
}

port_busy() {
  lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
}

printf '== 1. 基础设施（PostgreSQL 55434 / Redis 56379）==\n'
cd "$ROOT/backend"
docker compose -p shang-pivot-auto up -d postgres redis >/dev/null
for _ in $(seq 1 30); do
  if docker exec shang-postgres pg_isready -U shang -d shang >/dev/null 2>&1; then
    echo "  ✓ PostgreSQL 就绪"; break
  fi
  sleep 1
done

printf '\n== 2. 后端（8086）==\n'
if ! port_busy 8086; then
  JAR="$ROOT/backend/shang-server/target/shang-server-0.0.1-SNAPSHOT.jar"
  if [ ! -f "$JAR" ]; then
    echo "  构建后端..."
    JAVA_HOME="$JAVA_HOME" "$MVN" -q -DskipTests -pl shang-server -am package
  fi
  nohup "$JAVA_HOME/bin/java" -jar "$JAR" >"$LOG_DIR/backend.log" 2>&1 &
  echo "  后端已启动 pid=$!，日志 $LOG_DIR/backend.log"
else
  echo "  8086 已有进程，跳过"
fi

printf '\n== 3. 管理端（5173）==\n'
if ! port_busy 5173; then
  (cd "$ROOT/shang-admin-web" && nohup npm run dev -- --host 127.0.0.1 --port 5173 >"$LOG_DIR/admin.log" 2>&1 &)
  echo "  管理端已启动，日志 $LOG_DIR/admin.log"
else
  echo "  5173 已有进程，跳过"
fi

printf '\n== 4. 商城（5174）==\n'
if ! port_busy 5174; then
  (cd "$ROOT/shang-mall" && nohup npm run dev -- --host 127.0.0.1 --port 5174 >"$LOG_DIR/mall.log" 2>&1 &)
  echo "  商城已启动，日志 $LOG_DIR/mall.log"
else
  echo "  5174 已有进程，跳过"
fi

printf '\n== 5. 健康检查 ==\n'
wait_http "http://127.0.0.1:8086/api/home" "后端 API" 90
wait_http "http://127.0.0.1:5173/" "管理端" 60
wait_http "http://127.0.0.1:5174/" "商城" 60

cat <<EOF

本地环境就绪：
  API      http://127.0.0.1:8086
  管理端   http://127.0.0.1:5173   admin / Admin@123
  商城     http://127.0.0.1:5174   任意 11 位手机号 + 123456

下一步：
  cd automation && mvn test                       # 全量
  cd automation && mvn test -Dcucumber.filter.tags="@api"
  cd automation && mvn test -Dcucumber.filter.tags="@ui"
EOF
