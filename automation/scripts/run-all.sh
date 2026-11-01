#!/usr/bin/env bash
# 一键：启动环境 → 全量执行 → 生成 Allure 报告。
# 额外参数原样传给 Maven，例如：
#   bash scripts/run-all.sh -Dcucumber.filter.tags="@smoke"
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MVN="${MVN:-$(command -v mvn || true)}"
[ -n "$MVN" ] || MVN="/Users/imac/.m2/wrapper/dists/apache-maven-3.9.12/57e1dc81/bin/mvn"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}"

bash "$ROOT/automation/scripts/start-local.sh"

cd "$ROOT/automation"
echo
echo "== 执行测试 =="
"$MVN" clean test "$@"

echo
echo "== 生成报告 =="
bash "$ROOT/automation/scripts/generate-report.sh"
