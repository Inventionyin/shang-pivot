#!/usr/bin/env bash
# UI 基准：串行或并行跑 Playwright 用例，产物写入 artifacts/parallel/ui-<mode>-<stamp>/
# 用法：bash scripts/benchmark_ui.sh --mode serial|parallel [--workers 3]
set -euo pipefail
cd "$(dirname "$0")/.."
PYTHON="python3"; [ -x ".venv/bin/python" ] && PYTHON=".venv/bin/python"
MODE="parallel"; WORKERS=3
while [ $# -gt 0 ]; do
  case "$1" in
    --mode) MODE="${2:?}"; shift 2 ;;
    --workers) WORKERS="${2:?}"; shift 2 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done
STAMP="$(date +%Y%m%d-%H%M%S)"
DIR="artifacts/parallel/ui-${MODE}-${STAMP}"
mkdir -p "$DIR"
export RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
export ARTIFACTS_DIR="$DIR"
export UI_MALL_URL="${UI_MALL_URL:-http://localhost:5174}"
export UI_ADMIN_URL="${UI_ADMIN_URL:-http://localhost:5173}"
docker exec shang-redis sh -c "redis-cli --scan --pattern 'login:fail:*' | xargs -r redis-cli del" >/dev/null 2>&1 || true
ARGS=(-m ui -q --browser-channel=chrome --alluredir="$DIR/allure" --clean-alluredir)
if [ "$MODE" = "parallel" ]; then
  ARGS+=(-n "$WORKERS" --dist loadscope)
  WORKERS_USED="$WORKERS"
else
  WORKERS_USED=1
fi
started=$SECONDS
set +e
"$PYTHON" -m pytest "${ARGS[@]}"
rc=$?
set -e
total=$((SECONDS - started))
"$PYTHON" - "$DIR" "$total" "$rc" "$WORKERS_USED" <<'PY'
import json, sys
from pathlib import Path
run = Path(sys.argv[1]); total = int(sys.argv[2]); rc = int(sys.argv[3]); workers = int(sys.argv[4])
latest = {}
for file in sorted(run.glob("results*.json")):
    data = json.loads(file.read_text(encoding="utf-8"))
    for item in data.get("results") or []:
        latest[item.get("nodeid", "")] = item.get("outcome")
counts = {"passed": 0, "failed": 0, "skipped": 0}
for outcome in latest.values():
    if outcome in counts: counts[outcome] += 1
(run / "run-summary.json").write_text(json.dumps({
    "layer": "ui", "mode": "serial" if workers == 1 else "parallel",
    "workers": workers, "total_seconds": total, "exit": rc, **counts,
}, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"[done] UI {workers} worker {total}s -> {run}")
PY
