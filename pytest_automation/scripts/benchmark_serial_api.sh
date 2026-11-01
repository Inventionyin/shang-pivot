#!/usr/bin/env bash
# API 串行基准：单进程跑完整 API 套件，产物写入 artifacts/parallel/serial-<stamp>/
set -euo pipefail
cd "$(dirname "$0")/.."
PYTHON="python3"; [ -x ".venv/bin/python" ] && PYTHON=".venv/bin/python"
STAMP="$(date +%Y%m%d-%H%M%S)"
DIR="artifacts/parallel/serial-${STAMP}"
mkdir -p "$DIR"
export RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
export ARTIFACTS_DIR="$DIR"
docker exec shang-redis sh -c "redis-cli --scan --pattern 'login:fail:*' | xargs -r redis-cli del" >/dev/null 2>&1 || true
started=$SECONDS
set +e
"$PYTHON" -m pytest -m api -q --alluredir="$DIR/allure" --clean-alluredir
rc=$?
set -e
total=$((SECONDS - started))
"$PYTHON" - "$DIR" "$total" "$rc" <<'PY'
import json, sys
from pathlib import Path
run = Path(sys.argv[1]); total = int(sys.argv[2]); rc = int(sys.argv[3])
latest = {}
for line in (run / "results.json").read_text(encoding="utf-8").splitlines():
    pass
data = json.loads((run / "results.json").read_text(encoding="utf-8"))
for item in data.get("results") or []:
    latest[item.get("nodeid", "")] = item.get("outcome")
counts = {"passed": 0, "failed": 0, "skipped": 0}
for outcome in latest.values():
    if outcome in counts: counts[outcome] += 1
(run / "run-summary.json").write_text(json.dumps({
    "layer": "api", "mode": "serial", "workers": 1,
    "total_seconds": total, "exit": rc, **counts,
}, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"[done] API 串行基准 {total}s -> {run}")
PY
