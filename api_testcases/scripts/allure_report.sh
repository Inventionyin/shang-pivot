#!/usr/bin/env bash
# 生成 / 预览 Allure 报告。
#
# 背景：allure-pytest 只写「结果 JSON」，出 HTML 报告必须有 allure CLI（Java 实现）。
# 本项目通过 npm 在 api_testcases/ 下装了 allure-commandline（--no-save，不进 package.json 依赖）。
# 找不到本地 CLI 时才回退到全局 allure。
#
# 用法：
#   bash scripts/allure_report.sh                  # 生成报告到 artifacts/allure-report 并打印路径
#   bash scripts/allure_report.sh --serve          # 起临时服务并打开浏览器
#   bash scripts/allure_report.sh --results DIR    # 指定结果目录（默认 artifacts/allure）
#   bash scripts/allure_report.sh --clean          # 生成前清空结果目录（谨慎：可能含已入库文件）
set -euo pipefail

cd "$(dirname "$0")/.."
RESULTS="artifacts/allure"
REPORT="artifacts/allure-report"
SERVE=0
CLEAN=0

while [ $# -gt 0 ]; do
  case "$1" in
    --serve) SERVE=1; shift ;;
    --clean) CLEAN=1; shift ;;
    --results) RESULTS="${2:?--results 需要目录}"; shift 2 ;;
    --report)  REPORT="${2:?--report 需要目录}"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

# ---------- 定位 allure CLI ----------
ALLURE=""
if [ -x "node_modules/allure-commandline/bin/allure" ]; then
  ALLURE="node node_modules/allure-commandline/bin/allure"
elif command -v allure >/dev/null 2>&1; then
  ALLURE="allure"
else
  cat >&2 <<'EOF'
未找到 allure CLI。二选一：
  npm install --no-save allure-commandline      # 在 api_testcases/ 下执行（需联网；本机已用此方式装好）
  brew install allure                            # 或装全局
EOF
  exit 3
fi

if [ "$CLEAN" = "1" ]; then
  echo "⚠️  清空结果目录 ${RESULTS}（allure 结果属构建产物，但本仓库曾把部分结果入库，请自行确认）"
  rm -rf "$RESULTS"
fi

if [ ! -d "$RESULTS" ] || [ -z "$(ls -A "$RESULTS" 2>/dev/null)" ]; then
  echo "结果目录为空：$RESULTS" >&2
  echo "先跑用例，例如：" >&2
  echo "  python3 -m pytest tests/contract tests/ui -q --alluredir $RESULTS" >&2
  exit 4
fi

RESULT_FILES=$(find "$RESULTS" -name '*-result.json' | wc -l | tr -d ' ')
echo "结果文件：$RESULT_FILES 条用例结果（${RESULTS}）"

if [ "$SERVE" = "1" ]; then
  echo "启动 Allure 服务（Ctrl+C 结束）…"
  exec $ALLURE serve "$RESULTS"
fi

rm -rf "$REPORT"
$ALLURE generate "$RESULTS" -o "$REPORT" --clean
echo
echo "✅ 报告已生成：$REPORT/index.html"
echo "   打开：open $REPORT/index.html"
