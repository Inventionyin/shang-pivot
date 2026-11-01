#!/usr/bin/env bash
# 从运行中的 JaCoCo agent（tcpserver）落盘 exec，可选 reset 便于按轮统计。
# 用法：bash scripts/jacoco_dump.sh [--reset] [--dest artifacts/jacoco/jacoco.exec]
set -uo pipefail

cd "$(dirname "$0")/.."
JACOCO_VERSION="${JACOCO_VERSION:-0.8.12}"
CLI=".tools/jacoco/org.jacoco.cli-${JACOCO_VERSION}-nodeps.jar"
PORT="${JACOCO_PORT:-6300}"
DEST="artifacts/jacoco/jacoco.exec"
RESET=""
while [ $# -gt 0 ]; do
  case "$1" in
    --reset) RESET="--reset"; shift ;;
    --dest) DEST="${2:?--dest 需要文件路径}"; shift 2 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

[ -f "$CLI" ] || { echo "缺少 $CLI；先运行 scripts/start_backend_jacoco.sh" >&2; exit 3; }
mkdir -p "$(dirname "$DEST")"
java -jar "$CLI" dump --address 127.0.0.1 --port "$PORT" --destfile "$DEST" $RESET
echo "[jacoco] 已落盘 $DEST"
