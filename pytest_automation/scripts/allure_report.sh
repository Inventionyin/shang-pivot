#!/usr/bin/env bash
# 生成 / 预览 Allure HTML 报告。
# 依赖 allure CLI：优先用 api_testcases 下已装的 allure-commandline，其次全局 allure。
set -euo pipefail

cd "$(dirname "$0")/.."
RESULTS="artifacts/allure"
REPORT="artifacts/allure-report"
SERVE=0

while [ $# -gt 0 ]; do
  case "$1" in
    --serve) SERVE=1; shift ;;
    --results) RESULTS="${2:?--results 需要目录}"; shift 2 ;;
    --report) REPORT="${2:?--report 需要目录}"; shift 2 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

ALLURE=""
if [ -x "../api_testcases/node_modules/allure-commandline/bin/allure" ]; then
  ALLURE="node ../api_testcases/node_modules/allure-commandline/bin/allure"
elif [ -x "../.tools/allure-2.35.1/bin/allure" ]; then
  ALLURE="../.tools/allure-2.35.1/bin/allure"
elif command -v allure >/dev/null 2>&1; then
  ALLURE="allure"
else
  echo "未找到 allure CLI。二选一：" >&2
  echo "  (cd ../api_testcases && npm install --no-save allure-commandline)" >&2
  echo "  brew install allure" >&2
  exit 3
fi

if [ ! -d "$RESULTS" ]; then
  echo "结果目录不存在：$RESULTS；先跑 pytest --alluredir=$RESULTS" >&2
  exit 4
fi

# 保留上一轮报告的历史：Allure 趋势图（Trends）依赖 results/history
if [ -d "$REPORT/history" ]; then
  rm -rf "$RESULTS/history"
  cp -r "$REPORT/history" "$RESULTS/history"
fi

$ALLURE generate "$RESULTS" -o "$REPORT" --clean
if [ -d "artifacts/jacoco/html" ]; then
  mkdir -p "$REPORT/jacoco"
  rm -rf "$REPORT/jacoco/html"
  cp -r "artifacts/jacoco/html" "$REPORT/jacoco/html"
  mkdir -p "$REPORT/ext/jacoco"
  rm -rf "$REPORT/ext/jacoco/html"
  cp -r "artifacts/jacoco/html" "$REPORT/ext/jacoco/html"
fi
echo "报告已生成：$(cd "$REPORT" && pwd)/index.html"

# 生成接口视图（独立页面 + Allure 首页悬浮入口）
PYTHON="python3"
[ -x ".venv/bin/python" ] && PYTHON=".venv/bin/python"
$PYTHON scripts/build_api_matrix.py --report-dir "$REPORT" || \
  echo "[warn] 接口视图生成失败（不影响 Allure 报告）" >&2
$PYTHON scripts/build_ui_matrix.py --report-dir "$REPORT" || \
  echo "[warn] UI 浏览器矩阵生成失败（不影响 Allure 报告）" >&2
$PYTHON scripts/build_coverage_matrix.py || \
  echo "[warn] 覆盖率矩阵生成失败（不影响 Allure 报告）" >&2
$PYTHON scripts/build_defects.py --report-dir "$REPORT" || \
  echo "[warn] 缺陷单生成失败（不影响 Allure 报告）" >&2
$PYTHON scripts/build_benchmark_page.py --report-dir "$REPORT" || \
  echo "[warn] 并行对比页生成失败（不影响 Allure 报告）" >&2
$PYTHON scripts/archive_run.py || echo "[warn] 运行归档失败" >&2
$PYTHON scripts/case_history.py || echo "[warn] 历史 diff 失败" >&2
$PYTHON scripts/build_knowledge_index.py || echo "[warn] 知识库索引失败" >&2

if [ "$SERVE" = "1" ]; then
  $ALLURE open "$REPORT"
fi
