#!/bin/bash
# =============================================================================
# Allure 报告启动脚本
# 功能: 运行测试 → 生成 Allure 结果 → 生成/更新 HTML 报告 → 启动服务
# 用法:
#   ./scripts/run_allure.sh                  # 运行全部测试并打开报告
#   ./scripts/run_allure.sh all -m smoke     # 运行冒烟测试并打开报告
#   ./scripts/run_allure.sh report           # 仅从已有结果重新生成报告并打开
#   ./scripts/run_allure.sh serve            # 仅启动 Allure 服务(后台)
#   ./scripts/run_allure.sh open             # 仅打开已有报告
# =============================================================================

set -euo pipefail

# ---------- 路径配置 ----------
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ALLURE_RESULTS_DIR="$PROJECT_ROOT/reports/allure_results"
ALLURE_REPORT_DIR="$PROJECT_ROOT/reports/allure_report"

# ---------- 颜色输出 ----------
GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

info()  { echo -e "${CYAN}[INFO]${NC}  $1"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $1"; }
err()   { echo -e "${RED}[ERROR]${NC} $1"; }

# ---------- 前置检查 ----------
check_prerequisites() {
    if ! command -v allure &>/dev/null; then
        err "Allure 未安装！请执行: brew install allure"
        exit 1
    fi

    if ! command -v pytest &>/dev/null && ! python -m pytest --version &>/dev/null 2>&1; then
        err "pytest 未安装！请执行: pip install pytest"
        exit 1
    fi

    ALLURE_VERSION=$(allure --version)
    info "Allure 版本: $ALLURE_VERSION"
}

# ---------- 运行测试 ----------
run_tests() {
    local pytest_marker="${1:-}"

    mkdir -p "$ALLURE_RESULTS_DIR"

    info "清理旧的 Allure 结果..."
    rm -rf "${ALLURE_RESULTS_DIR:?}"/*

    cd "$PROJECT_ROOT"

    if [ -n "$pytest_marker" ]; then
        info "运行测试 (标记: $pytest_marker)..."
        python -m pytest -m "$pytest_marker" --alluredir="$ALLURE_RESULTS_DIR" --clean-alluredir
    else
        info "运行全部测试..."
        python -m pytest --alluredir="$ALLURE_RESULTS_DIR" --clean-alluredir
    fi

    ok "测试执行完成"
}

# ---------- 生成报告 ----------
generate_report() {
    info "生成 Allure 报告..."
    cd "$PROJECT_ROOT"
    allure generate "$ALLURE_RESULTS_DIR" -o "$ALLURE_REPORT_DIR" --clean
    ok "报告已生成: $ALLURE_REPORT_DIR/index.html"
}

# ---------- 启动服务 ----------
start_server() {
    local open_browser="${1:-true}"

    info "启动 Allure 服务..."

    if [ "$open_browser" = true ]; then
        allure open "$ALLURE_REPORT_DIR"
    else
        allure serve "$ALLURE_RESULTS_DIR" &
        SERVER_PID=$!
        info "Allure 服务已在后台启动 (PID: $SERVER_PID)"
        info "访问地址: http://127.0.0.1:${ALLURE_PORT:-任意端口}"
    fi
}

# ---------- 报告已存在时直接打开 ----------
open_report() {
    if [ -f "$ALLURE_REPORT_DIR/index.html" ]; then
        info "打开已有报告..."
        allure open "$ALLURE_REPORT_DIR"
    else
        warn "报告不存在，先重新生成..."
        generate_report
        allure open "$ALLURE_REPORT_DIR"
    fi
}

# =============================================================================
# 主流程
# =============================================================================
main() {
    echo ""
    echo -e "${CYAN}╔══════════════════════════════════════════════╗${NC}"
    echo -e "${CYAN}║        Allure Test Report Launcher          ║${NC}"
    echo -e "${CYAN}║             Allure 报告启动器               ║${NC}"
    echo -e "${CYAN}╚══════════════════════════════════════════════╝${NC}"
    echo ""

    check_prerequisites
    echo ""

    # 解析命名参数
    local mode="all"
    local pytest_marker=""
    local positional_args=()

    while [ $# -gt 0 ]; do
        case "$1" in
            -m|--marker)
                pytest_marker="$2"
                shift 2
                ;;
            -h|--help)
                show_usage
                exit 0
                ;;
            -*)
                err "未知选项: $1"
                show_usage
                exit 1
                ;;
            *)
                positional_args+=("$1")
                shift
                ;;
        esac
    done

    # 第一个 positional 参数是 mode
    if [ ${#positional_args[@]} -gt 0 ]; then
        mode="${positional_args[0]}"
    fi

    case "$mode" in
        all)
            run_tests "$pytest_marker"
            generate_report
            start_server true
            ;;
        report)
            generate_report
            start_server true
            ;;
        serve)
            start_server false
            ;;
        open)
            open_report
            ;;
        *)
            show_usage
            exit 1
            ;;
    esac
}

show_usage() {
    echo "用法: $0 [子命令] [选项]"
    echo ""
    echo "子命令 (默认: all):"
    echo "  all     运行测试 → 生成报告 → 打开浏览器"
    echo "  report  仅从已有结果重新生成报告并打开"
    echo "  serve   仅启动 Allure 服务(后台)"
    echo "  open    仅打开已有报告"
    echo ""
    echo "选项:"
    echo "  -m, --marker   指定 pytest 标记 (如 smoke, P0, regression)"
    echo "  -h, --help     显示帮助信息"
    echo ""
    echo "示例:"
    echo "  $0                    # 运行全部测试并打开报告"
    echo "  $0 all -m smoke       # 运行冒烟测试并打开报告"
    echo "  $0 all -m P0          # 运行 P0 用例"
    echo "  $0 report             # 重新生成报告"
    echo "  $0 serve              # 后台启动服务"
    echo "  $0 open               # 打开已有报告"
}

main "$@"