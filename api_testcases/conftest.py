"""全局 pytest 配置：加载所有 fixtures 和 Allure 报告钩子"""
import sys
import json
import os
import re
import datetime
import uuid
from pathlib import Path
import pytest

# 确保项目根目录在 path 中
ROOT = Path(__file__).parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

# 自动加载 fixtures
pytest_plugins = [
    "fixtures.api_fixtures",
]

# ============= Allure 报告增强钩子 =============

try:
    import allure  # type: ignore
    _ALLURE = True
except ImportError:
    _ALLURE = False


def pytest_configure(config):
    """pytest 启动时配置"""
    if _ALLURE:
        allure_dir = config.getoption("--alluredir", None)
        if allure_dir:
            # 生成环境信息附件
            # 注意：Allure 以 Latin-1 读取 environment.properties，写中文会乱码（实测踩过）。
            # 需要中文说明请写进 environment.xml（UTF-8），或保持此处 ASCII。
            env_info = {
                "Framework": "api_testcases (pytest + contract validator)",
                "Python": sys.version.split()[0],
                "Platform": sys.platform,
                "Admin Base": os.getenv("ADMIN_BASE_URL", "http://localhost:8086"),
                "Mall Base": os.getenv("MALL_BASE_URL", "http://localhost:8086"),
                "Schema Check": "on" if os.getenv("SCHEMA_CHECK", "1") == "1" else "off",
                "Write Cases": "run" if os.getenv("EXECUTE_WRITES", "0") == "1" else "skipped",
                "Bus Cases": "included" if os.getenv("INCLUDE_BUS", "1") == "1" else "excluded",
            }
            env_path = Path(allure_dir) / "environment.properties"
            env_path.parent.mkdir(parents=True, exist_ok=True)
            with open(env_path, "w") as f:
                for k, v in env_info.items():
                    f.write(f"{k}={v}\n")

            # 生成 executor 信息（CI 场景可扩展）
            executor_path = Path(allure_dir) / "executor.json"
            try:
                executor_info = {
                    "name": "pytest",
                    "type": "pytest",
                    "reportUrl": "",
                }
                with open(executor_path, "w") as f:
                    json.dump(executor_info, f, indent=2)
            except Exception:
                pass

            # 缺陷自动归类：报告直接按「回归类型」分桶，不用人工逐条判断（首个命中生效）
            categories = [
                {"name": "① 契约结构回归（schema 不符）", "matchedStatuses": ["failed", "broken"],
                 "messageRegex": ".*schema 不符.*"},
                {"name": "② Long 序列化回归（应为字符串）", "matchedStatuses": ["failed", "broken"],
                 "messageRegex": ".*Long 字段.*应为字符串.*"},
                {"name": "③ 可观测性缺失（traceId）", "matchedStatuses": ["failed", "broken"],
                 "messageRegex": ".*\\[L4可观测\\].*"},
                {"name": "④ 服务端 5xx（产品缺陷）", "matchedStatuses": ["failed", "broken"],
                 "messageRegex": ".*HTTP 期望 \\d+ 实际 5\\d\\d.*"},
                {"name": "⑤ 业务规则不符（业务码/状态/取值）", "matchedStatuses": ["failed", "broken"],
                 "messageRegex": ".*业务码期望.*|.*回查.*"},
                {"name": "⑥ 其余失败（需人工判定）", "matchedStatuses": ["failed", "broken"]},
            ]
            (Path(allure_dir) / "categories.json").write_text(
                json.dumps(categories, ensure_ascii=False, indent=2), encoding="utf-8")
    config._automation_results = []


def pytest_make_parametrize_id(config, val):
    """让 Allure 正确显示参数化用例名称"""
    if isinstance(val, dict) and "desc" in val:
        return val["desc"]
    return str(val)


# Allure 的 dynamic 标签只在**用例执行期**生效，collection 阶段设置无效，
# 因此用 autouse fixture 读取参数化传入的 case，按资源/类型/优先级分层打标。
_SEVERITY = {"P0": "blocker", "P1": "critical", "P2": "normal", "P3": "minor"}
_FEATURE = {
    "ADMIN_AUTH": "认证与令牌", "API_AUTH": "会员认证", "ADMIN_ORDERS": "订单",
    "ADMIN_PRODUCTS": "商品", "ADMIN_CATEGORIES": "类目", "ADMIN_BRANDS": "品牌",
    "ADMIN_STOCKS": "库存", "ADMIN_AFTER_SALES": "售后", "ADMIN_USERS": "用户",
    "ADMIN_ROLES": "角色", "ADMIN_PERMISSIONS": "菜单与权限", "API_CART": "购物车",
    "API_ORDERS": "订单", "API_FAVORITES": "收藏", "API_ADDRESSES": "收货地址",
    "API_PRODUCTS": "商品浏览",
}


def _feature_of(case: dict) -> str:
    cid = case.get("case_id", "")
    if cid.startswith("BUS-"):
        return "端到端业务链路"
    stem = re.sub(r"-\d+$", "", cid)
    if stem in _FEATURE:
        return _FEATURE[stem]
    seg = [s for s in case.get("path", "").split("/") if s and s not in ("admin", "api")]
    return seg[0] if seg else "未分类"


@pytest.fixture(autouse=True)
def _allure_labels(request):
    """给每条用例挂 epic/feature/story/severity/title，让报告可按业务与优先级钻取。"""
    if not _ALLURE:
        yield
        return
    callspec = getattr(request.node, "callspec", None)
    case = (callspec.params.get("case") if callspec else None)
    if isinstance(case, dict) and case.get("case_id"):
        allure.dynamic.epic("尚品汇 · 接口自动化")
        allure.dynamic.feature(_feature_of(case))
        allure.dynamic.story(f"{case.get('kind', '未分类')}用例")
        allure.dynamic.severity(_SEVERITY.get(case.get("priority", ""), "normal"))
        allure.dynamic.title(f"{case['case_id']}｜{case.get('title', '')}")
        for tag in (case.get("kind", ""), case.get("priority", "")):
            if tag:
                allure.dynamic.tag(tag)
    yield


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_makereport(item, call):
    """Persist redacted request/response + step/PNG evidence for every failure."""
    outcome = yield
    report = outcome.get_result()
    if report.outcome != "failed":
        if report.when == "call":
            _record_automation_result(item, report, None)
        return
    try:
        from evidence import write_failure_evidence
        response = None
        for value in item.funcargs.values():
            candidate = getattr(value, "last_response", None)
            if candidate is not None:
                response = candidate
        path = write_failure_evidence(item.nodeid, report, response)
        os.environ["LAST_FAILURE_EVIDENCE"] = str(path)
        _record_automation_result(item, report, path)
    except Exception as exc:
        # Evidence generation must never mask the original test failure.
        print(f"[evidence] unable to write failure evidence: {exc}")


def _record_automation_result(item, report, evidence_path):
    """Collect a stable sidecar result file for ZenTao synchronization."""
    config = item.config
    case_ids = []
    for marker in item.iter_markers(name="zt_case"):
        case_ids.extend(str(value) for value in marker.args)
        case_ids.extend(str(value) for value in marker.kwargs.get("case_ids", []))
    if not case_ids:
        return
    config._automation_results.append({
        "nodeid": item.nodeid,
        "outcome": report.outcome,
        "case_ids": case_ids,
        "duration_seconds": getattr(report, "duration", 0),
        "evidence_json": str(evidence_path) if evidence_path else "",
    })


def pytest_sessionfinish(session, exitstatus):
    output = Path(os.getenv("RESULTS_JSON", "artifacts/automation_results.json"))
    output.parent.mkdir(parents=True, exist_ok=True)
    # 结果新鲜度绑定：run_id + 起止时间 + junit 摘要，防止"改/触碰结果文件"绕过 C8。
    junit = Path(os.getenv("JUNIT_XML", str(output.parent / "junit.xml")))
    digest = ""
    if junit.exists():
        import hashlib
        digest = hashlib.sha256(junit.read_bytes()).hexdigest()
    payload = {
        "exit_status": exitstatus,
        "base_url": os.getenv("BASE_URL", ""),
        "run_id": os.getenv("RUN_ID", "") or uuid.uuid4().hex,
        "started_at": os.getenv("RUN_STARTED_AT", ""),
        "finished_at": datetime.datetime.now().isoformat(timespec="seconds"),
        "junit_xml": str(junit),
        "junit_sha256": digest,
        "results": getattr(session.config, "_automation_results", []),
    }
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    if not payload["results"]:
        print("[results] 警告：结果为空——没有任何测试挂 zt_case 标记，回写禅道会拿不到数据"
              "（检查 parametrize 是否带 marks=pytest.mark.zt_case(...)）")
