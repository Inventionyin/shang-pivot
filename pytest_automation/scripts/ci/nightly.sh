#!/usr/bin/env bash
# 夜间/定时回归入口（GitHub Actions / Gitee Go / 本地 cron 均可复用）。
#
# 步骤：用例导出与门禁 → 变更选测 → API/UI 执行 → 报告管线（含归档/历史/缺陷/知识库）
# 产物：artifacts/allure-report、artifacts/runs、artifacts/history、artifacts/ci/nightly-summary.json
#
# 环境变量：
#   MODE=full|affected      默认 full；affected 按 git diff 选测（配合 SINCE）
#   SINCE=origin/main       affected 模式的比较基准
#   SKIP_UI=1               跳过 UI
#   SKIP_GATES=1            跳过质量门禁（不建议）
#   STRICT_REVIEW=1         评审门禁 P0 债务也阻断
#   ADMIN_PASSWORD          管理端密码（无默认值，必须提供）
#   PYTEST_BIN              默认 .venv/bin/python -m pytest
set -uo pipefail

cd "$(dirname "$0")/../.."

MODE="${MODE:-full}"
SINCE="${SINCE:-origin/main}"
PYTHON="python3"
[ -x ".venv/bin/python" ] && PYTHON=".venv/bin/python"
PYTEST="${PYTEST_BIN:-$PYTHON -m pytest}"

mkdir -p artifacts/ci
STATUS=0
STARTED_AT="$(date '+%Y-%m-%d %H:%M:%S')"

step() { printf '\n=== %s ===\n' "$1"; }

# 0) 基线数据回填：artifacts/ 被 gitignore，全新 checkout 时从已入库的基线取
ENDPOINT_MODEL="../api_testcases/artifacts/endpoint-model.json"
if [ ! -f "$ENDPOINT_MODEL" ]; then
  LATEST_BASELINE=$(ls -1d ../api_testcases/baselines/*/ 2>/dev/null | sort | tail -1)
  if [ -n "$LATEST_BASELINE" ] && [ -f "${LATEST_BASELINE}endpoint-model.json" ]; then
    mkdir -p "$(dirname "$ENDPOINT_MODEL")"
    cp "${LATEST_BASELINE}endpoint-model.json" "$ENDPOINT_MODEL"
    echo "[prep] endpoint-model.json <- $LATEST_BASELINE"
  fi
fi

# 1) 用例注册表 / 质量门禁 / 评审
if [ "${SKIP_GATES:-0}" != "1" ]; then
  step "用例导出与质量门禁"
  $PYTHON scripts/export_cases.py || STATUS=1
  # db_contract 读取 artifacts/write-contracts.json（由 data/write_contracts.yaml 生成）
  $PYTHON scripts/build_write_contracts.py || STATUS=1
  $PYTHON scripts/case_quality_gate.py || STATUS=1
  REVIEW_ARGS=(--check)
  [ "${STRICT_REVIEW:-0}" = "1" ] && REVIEW_ARGS=(--check --strict-p0)
  $PYTHON scripts/review_cases.py "${REVIEW_ARGS[@]}" || STATUS=1
fi

# 2) 选测（affected 模式）
PYTEST_TARGET=(-m api)
if [ "$MODE" = "affected" ]; then
  step "按变更选测（since $SINCE）"
  $PYTHON scripts/select_tests.py --since "$SINCE" --fallback-smoke
  COUNT=$($PYTHON - <<'PY'
import json
print(json.load(open("artifacts/selection/selection.json"))["testCount"])
PY
)
  if [ "$COUNT" -gt 0 ]; then
    PYTEST_TARGET=(-m api "@artifacts/selection/nodeids.txt")
  else
    PYTEST_TARGET=(-m "api and smoke")
  fi
fi

# 3) API 回归
step "API 回归"
$PYTEST "${PYTEST_TARGET[@]}" -q --alluredir=artifacts/allure --clean-alluredir
API_RC=$?
[ "$API_RC" -ne 0 ] && STATUS=1

# 4) UI 回归：每个浏览器单独一轮（避免 pytest.ini 默认 --browser 叠加出重复变体）
if [ "${SKIP_UI:-0}" != "1" ]; then
  step "UI 回归"
  UI_PIDS=()
  UI_HEADED="${UI_HEADED:-1}"
  HEADED_ARGS=()
  [ "$UI_HEADED" = "1" ] && HEADED_ARGS+=(--headed)
  for BROWSER in ${UI_BROWSERS:-chromium firefox}; do
    echo "[ui] ${BROWSER}（并行，$([ "$UI_HEADED" = "1" ] && echo 有头 || echo 无头)）"
    $PYTEST -m ui -q -n 3 --dist loadscope "${HEADED_ARGS[@]}" --browser "$BROWSER" \
      -o addopts="--tb=short --strict-markers --basetemp=.pytest_tmp-$BROWSER --reruns 1 --reruns-delay 1" \
      --alluredir=artifacts/allure > "artifacts/ui-$BROWSER.log" 2>&1 &
    UI_PIDS+=($!)
  done
  for UI_PID in "${UI_PIDS[@]}"; do
    wait "$UI_PID" || STATUS=1
  done
fi

# 5) 汇总本轮全部结果，再生成报告
step "生成报告"
$PYTHON scripts/finalize_run.py || STATUS=1
bash scripts/allure_report.sh || STATUS=1
# 报告契约检查需要基线文件（由发布前归档产生）；首次运行/新克隆时没有基线，跳过而非判失败
if [ -f artifacts/api-matrix.baseline.json ]; then
  $PYTHON scripts/check_report_contract.py \
    --baseline artifacts/api-matrix.baseline.json \
    --current artifacts/allure-report/ext/data/api-matrix.json || STATUS=1
else
  echo "[report] 无基线 artifacts/api-matrix.baseline.json，跳过接口契约对比（首次运行）"
fi

# 6) CI 摘要
$PYTHON - "$STATUS" "$STARTED_AT" <<'PY'
import json, sys
from datetime import datetime
from pathlib import Path

status, started = int(sys.argv[1]), sys.argv[2]
root = Path("artifacts")
selection = {}
path = root / "selection" / "selection.json"
if path.exists():
    selection = json.loads(path.read_text(encoding="utf-8"))
manifest = {}
mpath = root / "run-manifest.json"
if mpath.exists():
    manifest = json.loads(mpath.read_text(encoding="utf-8"))
summary = {
    "startedAt": started,
    "finishedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
    "status": "passed" if status == 0 else "failed",
    "runId": manifest.get("run_id"),
    "git": manifest.get("git"),
    "counts": manifest.get("counts"),
    "selection": {
        "enabled": bool(selection),
        "mode": selection.get("fallback") or "full",
        "testCount": selection.get("testCount"),
        "affectedEndpoints": selection.get("affectedEndpoints"),
        "requirementChanges": selection.get("requirementChanges"),
    },
    "report": "artifacts/allure-report/index.html",
}
out = root / "ci" / "nightly-summary.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"[nightly] {summary['status']} -> {out}")
PY

exit "$STATUS"
