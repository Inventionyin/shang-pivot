"""pytest 全局入口：插件加载、Allure 标签、失败截图、结果文件。"""
from __future__ import annotations

import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path

import pytest

pytest_plugins = [
    "fixtures.api_fixtures",
    "fixtures.ui_fixtures",
]

try:
    import allure  # type: ignore
except ImportError:  # pragma: no cover
    allure = None

try:
    from playwright.sync_api import Page  # type: ignore
except ImportError:  # pragma: no cover
    Page = None


_ENV_LABELS = {
    "epic": "尚品汇 · 自动化测试",
    "api": "接口自动化",
    "ui": "UI 自动化",
    "e2e": "端到端链路",
    "security": "安全测试",
}
_SEVERITY = {"P0": "blocker", "P1": "critical", "P2": "normal"}

_META_FILE = Path(__file__).parent / "data" / "case_meta.yaml"
_meta_cache: dict | None = None


def _case_meta() -> dict:
    """加载数据/case_meta.yaml（用例中文标题、验证点、模块），供 Allure 展示。"""
    global _meta_cache
    if _meta_cache is None:
        try:
            import yaml

            with open(_META_FILE, "r", encoding="utf-8") as f:
                _meta_cache = yaml.safe_load(f) or {}
        except Exception as exc:  # noqa: BLE001 - 元数据缺失时退回默认展示
            print(f"[allure] 用例元数据加载失败：{exc}")
            _meta_cache = {}
    return _meta_cache


def _meta_for(nodeid: str) -> tuple[dict, dict]:
    """返回 (用例元数据, 模块元数据)；参数化后缀先剥离。"""
    meta = _case_meta()
    base = nodeid.split("[", 1)[0]
    parts = base.split("::")
    file_key, func = parts[0], parts[-1]
    case = (meta.get("cases") or {}).get(f"{file_key}::{func}") or {}
    module = (meta.get("modules") or {}).get(file_key) or {}
    return case, module


def _layer_for(markers: set) -> str:
    if "ui" in markers:
        return "UI 自动化"
    if "e2e" in markers:
        return "端到端链路"
    return "接口自动化"


# ---------- 用例编号：与 test_cases.md 的稳定 ID 一致 ----------
_case_ids: dict[str, str] = {}
_STABLE_IDS_FILE = Path(__file__).parent / "test_cases.json"
_stable_ids: dict[str, str] | None = None


def _load_stable_ids() -> dict[str, str]:
    """从用例库读取稳定编号：避免单条/子集运行导致编号从 001 重排。"""
    global _stable_ids
    if _stable_ids is not None:
        return _stable_ids
    _stable_ids = {}
    try:
        if _STABLE_IDS_FILE.exists():
            data = json.loads(_STABLE_IDS_FILE.read_text(encoding="utf-8"))
            for case in data.get("cases") or []:
                nodeid = case.get("nodeid") or case.get("script")
                case_id = case.get("case_id")
                if nodeid and case_id:
                    _stable_ids[nodeid] = case_id
    except Exception as exc:  # noqa: BLE001
        print(f"[allure] 稳定编号加载失败：{exc}")
    return _stable_ids


_primary_endpoint_cache: dict[str, str] | None = None


def _primary_endpoints() -> dict[str, str]:
    """nodeid → 主体接口（运行期映射文件里的首个调用端点）。"""
    global _primary_endpoint_cache
    if _primary_endpoint_cache is not None:
        return _primary_endpoint_cache
    _primary_endpoint_cache = {}
    try:
        path = Path(__file__).parent / "artifacts" / "case_endpoint_map.json"
        if path.exists():
            data = json.loads(path.read_text(encoding="utf-8"))
            for nodeid, endpoints in (data.get("tests") or {}).items():
                if endpoints:
                    _primary_endpoint_cache[nodeid] = endpoints[0]
    except Exception as exc:  # noqa: BLE001
        print(f"[xdist] 主体接口映射加载失败：{exc}")
    return _primary_endpoint_cache


def pytest_collection_modifyitems(config, items):
    """给每条用例分配稳定编号（如 API-ADDR-001）。

    编号规则与 scripts/export_cases.py 完全一致：按收集顺序、按模块前缀分组计数。
    标题前置编号后，报告内按名称排序即为用例号正序（Packages/Suites/Behaviors 通用）。
    """
    modules = _case_meta().get("modules") or {}
    stable = _load_stable_ids()
    primary = _primary_endpoints()
    counters: dict[str, int] = {}
    for item in items:
        known = stable.get(item.nodeid)
        if known:
            _case_ids[item.nodeid] = known
            group = known.rsplit("-", 1)[0]
            counters[group] = max(counters.get(group, 0), int(known.rsplit("-", 1)[1]))
            continue
        markers = {m.name for m in item.iter_markers()}
        if "e2e" in markers:
            case_type = "E2E"
        elif "ui" in markers:
            case_type = "UI"
        else:
            case_type = "API"
        file_key = item.nodeid.split("::")[0]
        prefix = (modules.get(file_key) or {}).get("prefix", "MISC")
        group = "E2E" if case_type == "E2E" else f"{case_type}-{prefix}"
        counters[group] = counters.get(group, 0) + 1
        _case_ids[item.nodeid] = f"{group}-{counters[group]:03d}"

        # 每接口独立并发：同一接口的用例固定落在同一个 xdist worker（顺序执行），
        # 不同接口之间并行；serial lane 不参与分组（第二阶段串行）。
        markers = {m.name for m in item.iter_markers()}
        if "serial" not in markers:
            endpoint = primary.get(item.nodeid) or item.nodeid.split("::")[0]
            item.add_marker(pytest.mark.xdist_group(name=f"ep::{endpoint}"))


def _git_commit() -> str:
    """当前仓库提交号（environment.properties 只能放 ASCII）。"""
    import subprocess

    try:
        return subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=Path(__file__).resolve().parents[1],
            capture_output=True,
            text=True,
            timeout=5,
        ).stdout.strip() or "-"
    except Exception:  # noqa: BLE001
        return "-"


def _backend_build() -> str:
    """被测后端 jar 的构建时间：区分「测试的是哪版代码」的关键信息。"""
    jar = Path(__file__).resolve().parents[1] / "backend" / "shang-server" / "target"
    try:
        jars = sorted(jar.glob("shang-server-*.jar"))
        if not jars:
            return "not-built"
        import datetime

        mtime = datetime.datetime.fromtimestamp(jars[-1].stat().st_mtime)
        return mtime.strftime("%Y-%m-%d %H:%M")
    except Exception:  # noqa: BLE001
        return "-"


def _db_version() -> str:
    """数据库地址 + Flyway 最新版本（按应用顺序，版本列是文本，不能 max 字典序）。"""
    try:
        from helpers import db

        row = db.query_one(
            "SELECT version FROM flyway_schema_history WHERE success "
            "ORDER BY installed_rank DESC LIMIT 1"
        )
        version = row["version"] if row and row.get("version") else "?"
        params = db._conn_params()
        return f"{params['dbname']}@{params['host']} flyway={version}"
    except Exception:  # noqa: BLE001
        return "unavailable"


def _allure_categories(out: Path) -> None:
    # 注意：Allure 允许一条用例命中多个分类，规则必须互斥；不设兜底分类，
    # 未命中的失败仍会在 Suites/Packages 里以红色呈现，不会丢信息。
    categories = [
        {"name": "① 环境/基础设施不可达", "matchedStatuses": ["failed", "broken"],
         "messageRegex": ".*(Connection refused|ERR_CONNECTION|NewConnectionError|数据库不可达|无法连接|Name or service not known).*"},
        {"name": "② 接口契约/结构不符", "matchedStatuses": ["failed", "broken"],
         "messageRegex": ".*(响应不是 JSON|响应体应为对象|缺少字段|缺少 records|Schema).*"},
        {"name": "③ 业务规则不符", "matchedStatuses": ["failed", "broken"],
         "messageRegex": ".*(业务码期望 \\d+ 实际 0|期望失败但业务码为 0|提示信息应包含|HTTP 期望|状态不允许|库存不足).*"},
        {"name": "④ 服务端 5xx / 系统异常", "matchedStatuses": ["failed", "broken"],
         "messageRegex": ".*(系统异常|50000|实际 5\\d\\d|HTTP 5\\d\\d|too many 5\\d\\d error responses|Internal Server Error).*"},
        {"name": "⑤ 数据一致性/不变量", "matchedStatuses": ["failed", "broken"],
         "messageRegex": ".*(守恒|不变量|基线指纹|幂等).*"},
        {"name": "⑥ UI 元素/导航超时", "matchedStatuses": ["failed", "broken"],
         "messageRegex": ".*(Timeout|locator|waiting for|net::ERR|ERR_).*"},
        {"name": "⑦ 用例执行异常（代码/数据问题）", "matchedStatuses": ["failed", "broken"],
         "messageRegex": ".*(KeyError|IndexError|TypeError|ValueError|AttributeError|RuntimeError|NameError).*"},
        {"name": "⑧ 安全/水平越权", "matchedStatuses": ["failed", "broken"],
         "messageRegex": ".*越权.*"},
    ]
    (out / "categories.json").write_text(
        json.dumps(categories, ensure_ascii=False, indent=2), encoding="utf-8"
    )




def pytest_configure(config):
    config._automation_results = []
    config._run_started_at = datetime.datetime.now().isoformat(timespec="seconds")


def pytest_sessionstart(session):
    """写 Allure 元数据（环境页 + 失败分类）。

    必须放在 sessionstart：`--clean-alluredir` 由 allure-pytest 在 configure
    阶段清理目录，写在 configure 会被随后清空。
    """
    config = session.config
    if allure is None:
        return
    allure_dir = config.getoption("--alluredir", None)
    if not allure_dir:
        return

    # environment.properties 按 Latin-1 读取，只能写 ASCII
    from config.settings import settings

    info = {
        "Framework": "pytest_automation (API + Playwright)",
        "Test_env": settings.env,
        "Mall_API": settings.api_mall_base,
        "Admin_API": settings.api_admin_base,
        "Mall_UI": settings.ui_mall_url,
        "Admin_UI": settings.ui_admin_url,
        "UI": "on" if settings.run_ui else "off",
        "Python": sys.version.split()[0],
        "Platform": f"{sys.platform}",
        "Git_commit": _git_commit(),
        "Backend_build": _backend_build(),
        "Database": _db_version(),
    }
    out = Path(allure_dir)
    out.mkdir(parents=True, exist_ok=True)
    with open(out / "environment.properties", "w", encoding="utf-8") as f:
        for key, value in info.items():
            f.write(f"{key}={value}\n")

    _allure_categories(out)


def pytest_report_header(config):
    """报告头暴露关键开关，避免“为什么被 skip”靠猜。"""
    from config.settings import settings

    return [
        f"test env: {settings.env}",
        f"admin credentials: {'loaded' if settings.admin_password else 'MISSING (.env.local / ADMIN_PASSWORD)'}",
        f"ui: {'on' if settings.run_ui else 'off'}",
    ]


@pytest.fixture(scope="session")
def browser_context_args(browser_context_args):
    """所有 UI 用例统一 1280x720；商城 <768px 会切 H5 形态，不能混测。"""
    return {**browser_context_args, "viewport": {"width": 1280, "height": 720}}


def _unescape_param_id(value: str) -> str:
    """pytest 会把非 ASCII 的参数 id 转成 \\uXXXX 存进 nodeid，展示前还原。"""
    if "\\u" not in value:
        return value

    def _replace(match):
        return chr(int(match.group(1), 16))

    return re.sub(r"\\u([0-9a-fA-F]{4})", _replace, value)


def _apply_allure_labels(item) -> None:
    """Allure 展示信息：中文标题/验证点 + 业务分组（epic/feature/story）+ 严重级别。

    必须在 call 阶段注入（夹具阶段设置会被 allure-pytest 的测试初始化覆盖）。
    数据源：data/case_meta.yaml；未登记的新用例退回「文件名 + 函数名美化」。
    """
    if allure is None:
        return
    markers = {m.name for m in item.iter_markers()}
    case, module = _meta_for(item.nodeid)
    layer = _layer_for(markers)

    title = case.get("title") or item.name.replace("test_", "").replace("_", " ")
    param_id = getattr(getattr(item, "callspec", None), "id", None)
    if param_id and param_id != "chromium":
        title = f"{title}［{_unescape_param_id(str(param_id))}］"
    case_id = _case_ids.get(item.nodeid, "")
    if case_id:
        title = f"{case_id} {title}"
    module_name = module.get("name") or Path(item.nodeid).stem

    allure.dynamic.epic(_ENV_LABELS["epic"])
    allure.dynamic.parent_suite(layer)
    allure.dynamic.suite(module_name)
    allure.dynamic.feature(module_name)
    allure.dynamic.story(title)
    allure.dynamic.title(title)
    if case.get("verify"):
        allure.dynamic.description(f"**验证点**：{case['verify']}")
    allure.dynamic.tag(layer)
    allure.dynamic.tag(module_name)
    # 精确关联：Allure 的 fullName 会丢失参数/类名，用标签保留 pytest nodeid
    allure.dynamic.label("pytest_nodeid", item.nodeid)

    for level, severity in _SEVERITY.items():
        if level in markers:
            allure.dynamic.severity(severity)
            break


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_call(item):
    _apply_allure_labels(item)
    try:
        from core import endpoint_recorder

        endpoint_recorder.set_phase("call")
    except Exception:  # noqa: BLE001
        pass
    yield
    try:
        from core import endpoint_recorder

        endpoint_recorder.set_phase("teardown")
    except Exception:  # noqa: BLE001
        pass


def pytest_runtest_setup(item):
    """标记当前用例与阶段：埋点据此把请求归到用例名下。"""
    try:
        from core import endpoint_recorder

        endpoint_recorder.set_current(item.nodeid)
        endpoint_recorder.set_phase("setup")
    except Exception:  # noqa: BLE001 - 埋点失败不影响用例
        pass


def pytest_runtest_teardown(item, nextitem):
    try:
        from core import endpoint_recorder

        endpoint_recorder.set_phase("teardown")
    except Exception:  # noqa: BLE001
        pass


def _safe_name(nodeid: str) -> str:
    return re.sub(r"[^0-9A-Za-z_.-]+", "_", nodeid).strip("_")[:180]


@pytest.fixture(scope="session", autouse=True)
def _db_baseline_guard():
    """执行前后对比数据库指纹：演示数据被写用例污染时留下可追溯记录。

    只记录不阻断——写用例产生隔离会员/订单属预期，这里负责让「预期之外」可见。
    输出 artifacts/db-baseline-diff.json 并挂到 Allure。
    """
    from config.settings import settings
    from helpers import db

    # 并行执行时每个 worker 都会写同一个指纹文件，容易互相覆盖，直接关闭
    if os.getenv("DISABLE_DB_GUARD") == "1":
        yield None
        return

    if not db.available():
        yield None
        return

    before = db.fingerprint()
    yield before

    try:
        after = db.fingerprint()
        payload = {"before": before, "after": after, "changed": db.diff(before, after)}
        out = settings.artifacts_dir / "db-baseline-diff.json"
        out.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        if allure is not None:
            allure.attach(
                json.dumps(payload["changed"], ensure_ascii=False, indent=2) or "{}",
                name="数据库基线差异（执行后）",
                attachment_type=allure.attachment_type.JSON,
            )
    except Exception as exc:  # noqa: BLE001 - 指纹失败不能掩盖用例结果
        print(f"[db] 基线指纹对比失败：{exc}")


def _attach_failure_evidence(item, report) -> None:
    """失败/阻断留痕：失败信息 + 最后请求响应 + traceId 后端日志。

    Allure 里表现为一条「失败依据」附件 + 每个 traceId 一份日志片段，
    即使现场已恢复（如服务重启）也能凭附件复盘。
    """
    if allure is None:
        return
    try:
        from urllib.parse import urlparse

        from helpers.diagnose import attach

        lines = [
            f"用例：{item.nodeid}",
            f"阶段：{report.when}",
            "",
            "失败信息：",
            str(report.longrepr)[:4000],
        ]
        trace_ids: list[str] = []
        http_status = None
        error_trace = ""
        clients = []
        for value in item.funcargs.values():
            if isinstance(value, dict):
                clients.extend(value.values())
            elif isinstance(value, (list, tuple)):
                clients.extend(value)
            else:
                clients.append(value)
        for value in clients:
            client = getattr(value, "client", value)
            resp = getattr(client, "last_response", None)
            for tid in getattr(client, "trace_ids", []) or []:
                if tid not in trace_ids:
                    trace_ids.append(str(tid))
            if resp is None:
                continue
            if resp.status_code >= 500 or http_status is None:
                http_status = resp.status_code
            try:
                body = resp.json()
            except ValueError:
                body = None
            request = resp.request
            path = urlparse(request.url).path or request.url
            lines += [
                "",
                f"最后请求：{request.method} {path}",
                f"响应状态：HTTP {resp.status_code}",
                f"响应体：{json.dumps(body, ensure_ascii=False)[:2000] if body is not None else resp.text[:2000]}",
            ]
            trace_id = body.get("traceId") if isinstance(body, dict) else None
            if trace_id and trace_id not in trace_ids:
                trace_ids.append(str(trace_id))
                lines.append(f"traceId：{trace_id}")
            if trace_id and resp.status_code >= 500 and not error_trace:
                error_trace = str(trace_id)
        # 复用型 client 会带历史 traceId；只留最近几条，避免逐个扫描日志
        trace_ids = trace_ids[-5:]
        if trace_ids:
            lines += ["", "后端日志：见同用例下「后端日志（按 traceId）」附件"]
        if http_status and int(http_status) >= 500 and trace_ids:
            from helpers.diagnose import trace_excerpt

            excerpt = trace_excerpt(error_trace or trace_ids[-1])
            if excerpt:
                allure.attach(
                    excerpt,
                    name="后端日志（500）",
                    attachment_type=allure.attachment_type.TEXT,
                )
        allure.attach(
            "\n".join(lines),
            name="失败依据",
            attachment_type=allure.attachment_type.TEXT,
        )
        for trace_id in trace_ids:
            attach(trace_id)
    except Exception as exc:  # noqa: BLE001 - 诊断信息失败不能掩盖原始失败
        print(f"[diagnose] 失败依据附件生成失败：{exc}")


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_makereport(item, call):
    outcome = yield
    report = outcome.get_result()
    markers = {m.name for m in item.iter_markers()}
    if report.when == "call":
        item.config._automation_results.append(
            {
                "nodeid": item.nodeid,
                "outcome": report.outcome,
                "duration_seconds": round(getattr(report, "duration", 0), 3),
            }
        )

    # 失败/阻断留痕：失败依据 + 最后请求响应 + traceId 后端日志
    if report.outcome == "failed":
        _attach_failure_evidence(item, report)

    # 用例结束后附加 Playwright trace（每个 UI 用例一份）
    if report.when == "teardown" and "ui" in markers and allure is not None:
        try:
            from fixtures.ui_fixtures import trace_path

            trace = trace_path(item.nodeid)
            if trace.exists():
                # 官方 MIME：Allure 前端据此内嵌 trace 查看器，而不是只给下载
                allure.attach.file(
                    str(trace),
                    name="Playwright Trace",
                    attachment_type="application/vnd.allure.playwright-trace",
                    extension="zip",
                )
        except Exception as exc:  # noqa: BLE001 - 附件失败不能掩盖用例结果
            print(f"[ui] Playwright trace 附件失败：{exc}")

    if report.outcome != "failed" or Page is None:
        return

    # 失败留证：UI 用例截图并挂到 Allure
    for value in item.funcargs.values():
        page = value if isinstance(value, Page) else getattr(value, "page", None)
        if not isinstance(page, Page):
            continue
        try:
            from config.settings import settings

            target = settings.artifacts_dir / "ui" / "failures" / f"{_safe_name(item.nodeid)}.png"
            target.parent.mkdir(parents=True, exist_ok=True)
            page.screenshot(path=str(target), full_page=True)
            if allure is not None:
                allure.attach.file(
                    str(target),
                    name="失败截图",
                    attachment_type=allure.attachment_type.PNG,
                )
        except Exception as exc:  # noqa: BLE001 - 留证失败不能掩盖原始失败
            print(f"[ui] 失败截图写入失败：{exc}")


def _git_meta() -> dict:
    repo = Path(__file__).resolve().parents[1]

    def run(args):
        try:
            return subprocess.run(args, cwd=repo, capture_output=True, text=True, timeout=5).stdout.strip()
        except Exception:  # noqa: BLE001
            return ""

    dirty = bool(run(["git", "status", "--porcelain"]))
    return {
        "commit": run(["git", "rev-parse", "--short", "HEAD"]) or "-",
        "branch": run(["git", "rev-parse", "--abbrev-ref", "HEAD"]) or "-",
        "dirty": dirty,
    }


def _case_library_hash() -> str:
    path = Path(__file__).parent / "test_cases.json"
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()[:16]
    except OSError:
        return "-"


def _baseline_label() -> str:
    root = Path(__file__).resolve().parents[1] / "api_testcases" / "baselines"
    try:
        dirs = sorted([p.name for p in root.iterdir() if p.is_dir()])
        return dirs[-1] if dirs else "-"
    except OSError:
        return "-"


def pytest_sessionfinish(session, exitstatus):
    from config.settings import settings

    # --collect-only 也会触发 sessionfinish；不要把上一轮结果覆盖成空
    if session.config.option.collectonly:
        return

    worker = os.getenv("PYTEST_XDIST_WORKER", "master")
    suffix = "" if worker == "master" else f".{worker}"
    run_id = os.getenv("RUN_ID") or datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    git = _git_meta()
    raw_results = getattr(session.config, "_automation_results", [])
    # pytest-rerunfailures 会为同一 nodeid 追加多次 call 记录；保留最后一次结果
    by_nodeid = {}
    for item in raw_results:
        by_nodeid[item["nodeid"]] = item
    results = list(by_nodeid.values())
    counts = {"passed": 0, "failed": 0, "skipped": 0}
    for item in results:
        if item.get("outcome") in counts:
            counts[item["outcome"]] += 1
    payload = {
        "exit_status": int(exitstatus),
        "run_id": run_id,
        "git": git,
        "case_library_hash": _case_library_hash(),
        "baseline": _baseline_label(),
        "env": settings.env,
        "mall_api": settings.api_mall_base,
        "admin_api": settings.api_admin_base,
        "worker": worker,
        "results": results,
    }
    output = settings.artifacts_dir / f"results{suffix}.json"
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    manifest = {
        "run_id": run_id,
        "worker": worker,
        "started_at": getattr(session.config, "_run_started_at", ""),
        "finished_at": datetime.datetime.now().isoformat(timespec="seconds"),
        "git": git,
        "env": settings.env,
        "case_library_hash": _case_library_hash(),
        "baseline": _baseline_label(),
        "results_file": output.name,
        "counts": counts,
        "total": len(results),
    }
    (settings.artifacts_dir / f"run-manifest{suffix}.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    try:  # 用例→接口映射（每个 worker 独立落盘，发布前由合并脚本汇总）
        if os.getenv("DISABLE_RECORDER") == "1":
            return
        from core import endpoint_recorder

        endpoint_recorder.save(
            settings.artifacts_dir / f"case_endpoint_map{suffix}.json"
        )
    except Exception as exc:  # noqa: BLE001
        print(f"[recorder] 映射保存失败：{exc}")
