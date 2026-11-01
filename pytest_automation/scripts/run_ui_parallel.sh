#!/usr/bin/env bash
# 多浏览器并行 UI 回归（默认有头模式，本地可看到浏览器窗口）。
#
# 用法：
#   bash scripts/run_ui_parallel.sh                 # Chromium + Firefox，有头，各 3 worker
#   UI_HEADED=0 bash scripts/run_ui_parallel.sh     # 无头（CI）
#   UI_BROWSERS="chromium firefox webkit" bash scripts/run_ui_parallel.sh
#
# 说明：
#   - 每个浏览器一个 pytest 进程，进程内 xdist 并行；
#   - 每个进程必须使用独立 --basetemp，否则 xdist 会因共享目录 INTERNALERROR；
#   - 结果写入同一个 artifacts/allure，最后 finalize_run.py 汇总为一份口径。
set -uo pipefail

cd "$(dirname "$0")/.."
PYTHON="python3"
[ -x ".venv/bin/python" ] && PYTHON=".venv/bin/python"

BROWSERS="${UI_BROWSERS:-chromium firefox}"
WORKERS="${UI_WORKERS:-3}"
HEADED="${UI_HEADED:-1}"
UI_MALL_URL="${UI_MALL_URL:-http://127.0.0.1:5174}"
UI_ADMIN_URL="${UI_ADMIN_URL:-http://127.0.0.1:5173}"
export TEST_ENV="${TEST_ENV:-dev}"
export UI_MALL_URL UI_ADMIN_URL

ARGS=(-m ui -q -n "$WORKERS" --dist loadscope)
if [ "$HEADED" = "1" ]; then
  ARGS+=(--headed)
fi

mkdir -p artifacts
PIDS=()
for BROWSER in $BROWSERS; do
  echo "[ui] ${BROWSER}（$([ "$HEADED" = "1" ] && echo 有头 || echo 无头)，${WORKERS} worker）"
  $PYTHON -m pytest "${ARGS[@]}" --browser "$BROWSER" \
    -o addopts="--tb=short --strict-markers --basetemp=.pytest_tmp-$BROWSER --reruns 1 --reruns-delay 1" \
    --alluredir=artifacts/allure > "artifacts/ui-$BROWSER.log" 2>&1 &
  PIDS+=($!)
done

STATUS=0
for PID in "${PIDS[@]}"; do
  wait "$PID" || STATUS=1
done

$PYTHON scripts/finalize_run.py || STATUS=1
bash scripts/allure_report.sh || STATUS=1

echo "[ui] 日志：artifacts/ui-<browser>.log"
exit "$STATUS"
