#!/usr/bin/env bash
# 将本地 HTML 原型 capture 到 Figma
# 用法: ./capture-to-figma.sh <capture-id> <page-path>
# 例:   ./capture-to-figma.sh 8eb3e703-... web/home.html
#
# 前置: python3 -m http.server 8766 已在 store 目录运行
# Figma fileKey: 1NgQOBqkQGYhFO3bG2z8wz

set -euo pipefail
CAPTURE_ID="${1:?capture ID required}"
PAGE="${2:?page path required, e.g. web/home.html}"
BASE="http://127.0.0.1:8766"
ENDPOINT="https://mcp.figma.com/mcp/capture/${CAPTURE_ID}/submit?bindVariables=true"
ENCODED=$(python3 -c "import urllib.parse; print(urllib.parse.quote('${ENDPOINT}', safe=''))")
URL="${BASE}/${PAGE}#figmacapture=${CAPTURE_ID}&figmaendpoint=${ENCODED}&figmadelay=4000"
echo "Opening: ${URL}"
open "${URL}"
