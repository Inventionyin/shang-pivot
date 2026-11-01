"""用例 → 接口 映射记录器（运行期埋点）。

目的：Allure 结果只记录用例状态，不知道用例打了哪些接口。这里在 ApiClient 层
记录「当前用例 nodeid → 实际请求的 METHOD + 具体路径」，并在会话结束时落盘：

    artifacts/case_endpoint_map.json
    {
      "tests":    {"<nodeid>": ["GET /api/products/{spuId}", ...]},
      "samples":  {"<METHOD path模板>": {"status": 200, "body": {...脱敏示例...}}}
    }

具体路径（含真实 ID）在落盘前按端点模型归一化为模板路径（/api/orders/{id}），
供报告按接口聚合使用。示例响应只取首个 2xx 且 code=0 的响应，脱敏并截断。
"""
from __future__ import annotations

import json
import re
import threading
from pathlib import Path
from urllib.parse import urlparse

from core.logger import get_logger

log = get_logger("recorder")

_MODEL_FILE = (
    Path(__file__).resolve().parents[2]
    / "api_testcases"
    / "artifacts"
    / "endpoint-model.json"
)

_lock = threading.Lock()
_current_nodeid: str = ""
_phase: str = ""
_calls: dict[str, dict[str, list[str]]] = {}
_details: dict[str, dict[str, dict]] = {}
_samples: dict[str, dict] = {}
_templates: list[tuple[str, re.Pattern, str]] | None = None

_MASK_KEYS = ("token", "password", "secret", "authorization", "smscode")
_PHONE_RE = re.compile(r"^1\d{10}$")


def set_current(nodeid: str) -> None:
    global _current_nodeid
    _current_nodeid = nodeid or ""


def set_phase(phase: str) -> None:
    """setup/call/teardown：call 阶段为主归属，setup 阶段用于夹具驱动用例的兜底。"""
    global _phase
    _phase = phase


def _load_templates() -> list[tuple[str, re.Pattern, str]]:
    """端点模型路径 → (方法, 正则, 模板)。具体路径按模板归一化。"""
    global _templates
    if _templates is not None:
        return _templates
    _templates = []
    try:
        data = json.loads(_MODEL_FILE.read_text(encoding="utf-8"))["endpoints"]
        for item in data:
            path = item["path"]
            pattern = "^" + re.sub(r"\{[^}]+\}", "[^/]+", path) + "$"
            _templates.append((item["method"], re.compile(pattern), path))
    except Exception as exc:  # noqa: BLE001 - 模型缺失时退化为原路径
        log.warning("端点模型不可用，映射将使用具体路径：%s", exc)
    return _templates


def normalize(method: str, path: str) -> str:
    for m, pattern, template in _load_templates():
        if m == method and pattern.match(path):
            return f"{method} {template}"
    return f"{method} {path}"


def _sanitize(value, depth: int = 0):
    if depth > 6:
        return "..."
    if isinstance(value, dict):
        result = {}
        for key, item in value.items():
            if any(token in str(key).lower() for token in _MASK_KEYS):
                result[key] = "***"
            else:
                result[key] = _sanitize(item, depth + 1)
        return result
    if isinstance(value, list):
        return [_sanitize(item, depth + 1) for item in value[:3]]
    if isinstance(value, str):
        if _PHONE_RE.match(value):
            return value[:3] + "****" + value[-4:]
        return value if len(value) <= 160 else value[:157] + "..."
    return value


def record(method: str, url: str, status: int, body, request_payload: dict | None = None) -> None:
    """ApiClient 每次请求后调用；线程安全、失败不影响用例。"""
    if _phase == "teardown":
        return
    try:
        path = urlparse(url).path or url
        key = normalize(method.upper(), path)
        with _lock:
            if _current_nodeid:
                phases = _calls.setdefault(_current_nodeid, {})
                calls = phases.setdefault(_phase or "call", [])
                if key not in calls:
                    calls.append(key)
                detail = _details.setdefault(_current_nodeid, {})
                existing = detail.get(key)
                # call 阶段的快照优先于 setup 阶段（setup 是夹具准备数据）
                if existing is None or (_phase or "call") == "call":
                    detail[key] = {
                        "phase": _phase or "call",
                        "request": {
                            "query": _sanitize((request_payload or {}).get("query")),
                            "body": _sanitize((request_payload or {}).get("body")),
                            "raw": _sanitize((request_payload or {}).get("raw")),
                        },
                        "response": {
                            "status": status,
                            "body": _sanitize(body),
                        },
                    }
            if (
                key not in _samples
                and 200 <= status < 300
                and isinstance(body, dict)
                and body.get("code") == 0
            ):
                _samples[key] = {
                    "status": status,
                    "body": _sanitize(body),
                }
    except Exception:  # noqa: BLE001 - 埋点不能影响请求
        pass


def save(path: Path) -> None:
    try:
        merged_tests: dict[str, list[str]] = {}
        merged_samples: dict[str, dict] = {}
        if path.exists():  # 多次运行（API/UI）合并，后跑不覆盖前跑
            try:
                old = json.loads(path.read_text(encoding="utf-8"))
                merged_tests.update(old.get("tests") or {})
                merged_samples.update(old.get("samples") or {})
            except Exception:  # noqa: BLE001
                pass
        for nodeid, phases in _calls.items():
            merged = set(merged_tests.get(nodeid, [])) | set(phases.get("call") or [])
            merged_tests[nodeid] = sorted(merged)
        merged_samples.update(_samples)
        # setup 阶段调用单独保存：供「只能用夹具建立数据」的用例做兜底归属
        setup_map = {
            nodeid: list(phases.get("setup") or []) for nodeid, phases in _calls.items()
        }
        merged_details: dict[str, dict] = {}
        if path.exists():
            try:
                old = json.loads(path.read_text(encoding="utf-8"))
                merged_details.update(old.get("details") or {})
            except Exception:  # noqa: BLE001
                pass
        merged_details.update(_details)
        payload = {
            "tests": merged_tests,
            "setup": setup_map,
            "samples": merged_samples,
            "details": merged_details,
        }
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        log.info("用例-接口映射已保存：%s（%d 条用例）", path, len(_calls))
    except Exception as exc:  # noqa: BLE001
        log.warning("用例-接口映射保存失败：%s", exc)


def reset() -> None:
    global _current_nodeid
    with _lock:
        _calls.clear()
        _details.clear()
        _samples.clear()
        _current_nodeid = ""
        _phase = ""
