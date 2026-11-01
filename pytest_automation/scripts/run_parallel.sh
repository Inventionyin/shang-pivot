#!/usr/bin/env bash
# 两阶段并行执行（API 层）：
#   阶段一：-n N 跑「非 serial」用例（库存按 worker 分片）
#   阶段二：串行跑 serial 用例（批量库存、登录失败限流等）
# 产物写入 artifacts/parallel/<时间戳>/；--publish 时合并并重建接口视图报告。
#
# 用法：
#   ADMIN_PASSWORD='***' bash scripts/run_parallel.sh            # 4 worker，worksteal
#   WORKERS=8 bash scripts/run_parallel.sh                      # 指定 worker 数
#   DIST=loadscope bash scripts/run_parallel.sh                 # 指定分发策略
#   bash scripts/run_parallel.sh --publish                      # 合并产物并发布报告
set -euo pipefail
cd "$(dirname "$0")/.."

PYTHON="python3"
[ -x ".venv/bin/python" ] && PYTHON=".venv/bin/python"
WORKERS="${WORKERS:-4}"
DIST="${DIST:-loadgroup}"
PUBLISH=0

while [ $# -gt 0 ]; do
  case "$1" in
    --publish) PUBLISH=1; shift ;;
    --workers) WORKERS="${2:?}"; shift 2 ;;
    --dist) DIST="${2:?}"; shift 2 ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

STAMP="$(date +%Y%m%d-%H%M%S)"
RUN_DIR="artifacts/parallel/${STAMP}"
mkdir -p "${RUN_DIR}"
export RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
export ARTIFACTS_DIR="${RUN_DIR}"

echo "== 运行目录：${RUN_DIR}（workers=${WORKERS}, dist=${DIST}）"

# 清理上一轮登录失败计数（Redis 15 分钟窗口，会把多轮失败累加导致 40104）
docker exec shang-redis sh -c "redis-cli --scan --pattern 'login:fail:*' | xargs -r redis-cli del" >/dev/null 2>&1 || true

# 执行前数据库指纹（与串行报告同 schema）
${PYTHON} - "${RUN_DIR}" <<'PY'
import json, sys
from pathlib import Path
from helpers import db
Path(sys.argv[1], "db-before.json").write_text(
    json.dumps(db.fingerprint(), ensure_ascii=False, indent=2), encoding="utf-8"
)
PY

stage() {
  local name="$1"; shift
  local started=${SECONDS}
  set +e
  "$@"
  local rc=$?
  set -e
  echo "[time] ${name}: $((SECONDS - started))s（exit=${rc}）"
  echo "$((SECONDS - started))" > "${RUN_DIR}/.time-${name}"
  echo "${rc}" > "${RUN_DIR}/.rc-${name}"
}

# 阶段一：并行（关闭每 worker 的基线指纹，避免多进程抢写）
stage stage1 env DISABLE_DB_GUARD=1 \
  "${PYTHON}" -m pytest -m "api and not serial" -q \
  -n "${WORKERS}" --dist "${DIST}" \
  --alluredir="${RUN_DIR}/allure" --clean-alluredir

# 阶段二：串行（批量库存、登录限流类）
stage stage2 "${PYTHON}" -m pytest -m "api and serial" -q \
  --alluredir="${RUN_DIR}/allure"

# 合并 worker 产物
stage merge "${PYTHON}" scripts/merge_parallel_artifacts.py --run-dir "${RUN_DIR}"

# 执行后指纹 diff
${PYTHON} - "${RUN_DIR}" <<'PY'
import json, sys
from pathlib import Path
from helpers import db
run = Path(sys.argv[1])
before = json.loads((run / "db-before.json").read_text(encoding="utf-8"))
after = db.fingerprint()
(run / "db-baseline-diff.json").write_text(
    json.dumps({"before": before, "after": after, "changed": db.diff(before, after)},
               ensure_ascii=False, indent=2), encoding="utf-8"
)
PY

STAGE1=$(cat "${RUN_DIR}/.time-stage1"); STAGE2=$(cat "${RUN_DIR}/.time-stage2")
RC1=$(cat "${RUN_DIR}/.rc-stage1"); RC2=$(cat "${RUN_DIR}/.rc-stage2")
TOTAL=$((STAGE1 + STAGE2))
${PYTHON} - "${RUN_DIR}" "${WORKERS}" "${DIST}" "${STAGE1}" "${STAGE2}" "${TOTAL}" <<'PY'
import json, sys
from pathlib import Path
run, workers, dist, s1, s2, total = sys.argv[1:7]
(Path(run) / "run-summary.json").write_text(json.dumps({
    "workers": int(workers), "dist": dist,
    "stage1_seconds": int(s1), "stage2_seconds": int(s2), "total_seconds": int(total),
}, ensure_ascii=False, indent=2), encoding="utf-8")
PY
echo "== 总耗时：${TOTAL}s（阶段一 ${STAGE1}s / 阶段二 ${STAGE2}s）"

if [ "${PUBLISH}" = "1" ]; then
  echo "== 发布并行结果到报告（保留 UI 结果）"
  "${PYTHON}" scripts/publish_parallel.py --run-dir "${RUN_DIR}" --artifacts artifacts
  bash scripts/allure_report.sh
fi
