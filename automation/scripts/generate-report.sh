#!/usr/bin/env bash
# 从 target/allure-results 生成静态 HTML 报告到 artifacts/allure-report。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ALLURE_BIN="${ALLURE_BIN:-$(command -v allure || true)}"
[ -n "$ALLURE_BIN" ] || ALLURE_BIN="$HOME/.local/bin/allure"
if [ ! -x "$ALLURE_BIN" ]; then
  cat >&2 <<'EOF'
未找到 allure 命令。安装方式：
  1) brew install allure
  2) 或下载 CLI：
     curl -fLo /tmp/allure.zip https://repo1.maven.org/maven2/io/qameta/allure/allure-commandline/2.34.1/allure-commandline-2.34.1.zip
     unzip -q /tmp/allure.zip -d ~/.local/opt
     ln -sfn ~/.local/opt/allure-2.34.1/bin/allure ~/.local/bin/allure
EOF
  exit 1
fi

RESULTS="$ROOT/automation/target/allure-results"
REPORT="$ROOT/automation/artifacts/allure-report"
[ -d "$RESULTS" ] || { echo "没有找到 $RESULTS，请先执行 mvn test" >&2; exit 1; }

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}"
"$ALLURE_BIN" generate "$RESULTS" -o "$REPORT" --clean

echo "报告已生成: $REPORT/index.html"
echo "查看方式（Allure 报告必须通过 HTTP 访问，不能直接 file:// 打开）:"
echo "  python3 -m http.server 18080 --directory \"$REPORT\""
echo "  然后打开 http://127.0.0.1:18080/"
