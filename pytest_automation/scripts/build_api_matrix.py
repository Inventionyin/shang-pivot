#!/usr/bin/env python3
"""生成「接口视图」页面（报告站点内的独立 tab 页）。

数据来源：
- 接口清单/名称/鉴权：api_testcases/artifacts/endpoint-model.json（135 端点）
- 参数/返回值示例：docs/openapi-v3-api-docs.json（优先），真实响应样本兜底
- 用例→接口映射：pytest_automation/artifacts/case_endpoint_map.json
- 用例结果：pytest_automation/artifacts/allure/*-result.json

产物（相对报告目录）：
- ext/manifest.json          多视图清单（后续 UI 视图只需追加一项）
- ext/data/api-matrix.json   接口列表 + 示例 + 测试点
- ext/index.html, ext/assets/ 由 scripts/report_shell/ 静态复制
并自动向 Allure index.html 注入悬浮入口（幂等）。

用法：
    python3 scripts/build_api_matrix.py                 # 默认报告目录 artifacts/allure-report
    python3 scripts/build_api_matrix.py --report-dir X
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from urllib.parse import quote
from collections import OrderedDict
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
REPO = ROOT.parent
MODEL = REPO / "api_testcases" / "artifacts" / "endpoint-model.json"
OPENAPI = REPO / "docs" / "openapi-v3-api-docs.json"
MAP_FILE = ROOT / "artifacts" / "case_endpoint_map.json"
ATTRIBUTION_FILE = ROOT / "data" / "endpoint_attribution.yaml"
META_FILE = ROOT / "data" / "case_meta.yaml"

_SEVERITY_TO_PRIORITY = {"blocker": "P0", "critical": "P1", "normal": "P2"}
ALLURE_DIR = ROOT / "artifacts" / "allure"
SHELL_DIR = ROOT / "scripts" / "report_shell"

DIMENSION_ORDER = [
    "功能正向",
    "功能逆向",
    "安全",
    "边界值",
    "参数组合",
    "异常",
    "幂等",
    "大数据量",
    "业务不变量",
    "契约/其他",
]

_NEGATIVE_HINTS = (
    "拒绝", "被拒", "缺失", "缺少", "不存在", "失败", "非法", "不允许", "不可", "为空",
    "越权", "他人", "错误", "超长", "越界",
    "invalid", "missing", "not_found", "rejected", "wrong", "error", "requires_auth",
)


# ---------------- OpenAPI 示例生成 ----------------

class SchemaSampler:
    def __init__(self, spec: dict):
        self.spec = spec
        self.schemas = (spec.get("components") or {}).get("schemas") or {}

    def resolve(self, schema: dict, depth: int = 0) -> dict:
        if not isinstance(schema, dict) or depth > 8:
            return {}
        if "$ref" in schema:
            name = schema["$ref"].rsplit("/", 1)[-1]
            return self.resolve(self.schemas.get(name, {}), depth + 1)
        for key in ("allOf", "oneOf", "anyOf"):
            if key in schema and schema[key]:
                return self.resolve(schema[key][0], depth + 1)
        return schema

    def sample(self, schema: dict, depth: int = 0):
        schema = self.resolve(schema, depth)
        if depth > 6:
            return "..."
        if not schema:
            return None
        if "example" in schema:
            return schema["example"]
        if "default" in schema:
            return schema["default"]
        if schema.get("enum"):
            return schema["enum"][0]
        kind = schema.get("type")
        fmt = schema.get("format")
        if kind == "object" or "properties" in schema:
            props = schema.get("properties") or {}
            result = OrderedDict()
            for index, (key, value) in enumerate(props.items()):
                if index >= 8:
                    break
                result[key] = self.sample(value, depth + 1)
            return result
        if kind == "array":
            item = self.sample(schema.get("items") or {}, depth + 1)
            return [item] if item is not None else []
        if kind == "integer":
            return 1
        if kind == "number":
            return 9.9
        if kind == "boolean":
            return True
        if fmt == "date-time":
            return "2026-01-01T10:00:00+08:00"
        if fmt == "date":
            return "2026-01-01"
        return "string"


def _operation(spec: dict, method: str, path: str) -> dict:
    item = (spec.get("paths") or {}).get(path) or {}
    return item.get(method.lower()) or {}


_MASK_KEYS = ("password", "token", "secret", "authorization")


def _mask(value):
    """示例中的敏感字段脱敏（OpenAPI 示例可能带演示口令）。"""
    if isinstance(value, dict):
        return {
            key: ("***" if any(m in str(key).lower() for m in _MASK_KEYS) else _mask(item))
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [_mask(item) for item in value]
    return value


def _param_example(op: dict, sampler: SchemaSampler) -> dict:
    path_params, query_params = OrderedDict(), OrderedDict()
    body = None
    for param in op.get("parameters") or []:
        target = path_params if param.get("in") == "path" else query_params
        schema = param.get("schema") or {}
        value = param.get("example")
        if value is None:
            value = sampler.sample(schema)
        target[param.get("name", "param")] = value
    content = ((op.get("requestBody") or {}).get("content") or {})
    schema = (content.get("application/json") or {}).get("schema")
    if schema:
        body = sampler.sample(schema)
    example = OrderedDict()
    if path_params:
        example["path"] = path_params
    if query_params:
        example["query"] = query_params
    if body is not None:
        example["body"] = body
    return dict(example)


def _response_example(op: dict, sampler: SchemaSampler):
    responses = op.get("responses") or {}
    for status in ("200", "201", 200, 201):
        resp = responses.get(status)
        if not resp:
            continue
        schema = ((resp.get("content") or {}).get("application/json") or {}).get("schema")
        if schema:
            return sampler.sample(schema)
    return None


# ---------------- 用例结果与维度 ----------------

def load_risk_map() -> dict[tuple[str, str], str]:
    """风险级别以巡检数据为准（生成器统一计算），缺失时按 P2 处理。"""
    file = ROOT / "data" / "endpoint_matrix.json"
    risks: dict[tuple[str, str], str] = {}
    if file.exists():
        for case in json.loads(file.read_text(encoding="utf-8")):
            risks[(case["method"], case["path"])] = case.get("risk") or "P2"
    return risks


def load_suite_links(report_dir: Path) -> dict[str, str]:
    """Allure 2.35 的用例页路由是 #/suites/<祖先链>/<用例uid>/，不是 #/testcase/<uid>。

    从 data/suites.json 的树里取出每个叶子 uid 的祖先链，生成深链。
    """
    file = report_dir / "data" / "suites.json"
    if not file.exists():
        return {}
    tree = json.loads(file.read_text(encoding="utf-8"))
    links: dict[str, str] = {}

    def walk(node: dict, path: list[str]) -> None:
        uid = node.get("uid")
        current = path + [uid] if uid else path
        if node.get("status") and uid:
            # 路由只需「直接父分组 + 用例 uid」，完整祖先链会导致 401 Not found
            parent = current[-2] if len(current) >= 2 else ""
            if parent:
                links.setdefault(uid, f"{parent}/{uid}")
        for child in node.get("children") or []:
            walk(child, current)

    walk(tree, [])
    return links


def load_testcase_ids(report_dir: Path) -> dict[str, str]:
    """Allure 用例页按 test-case id（文件名）寻址，result uuid 不能用于深链。

    同名用例（重跑）取最后一次（time.start 最大）。
    """
    mapping: dict[str, tuple[int, str]] = {}
    cases_dir = report_dir / "data" / "test-cases"
    if not cases_dir.exists():
        return {}
    for file in cases_dir.glob("*.json"):
        try:
            data = json.loads(file.read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001
            continue
        name = data.get("name") or ""
        start = ((data.get("time") or {}).get("start")) or 0
        if name and (name not in mapping or start > mapping[name][0]):
            mapping[name] = (start, file.stem)
    return {name: value for name, (_, value) in mapping.items()}


def load_attribution() -> dict[str, list[str]]:
    """夹具驱动/仅数据库校验用例的接口归属覆盖。"""
    if not ATTRIBUTION_FILE.exists():
        return {}
    try:
        import yaml

        data = yaml.safe_load(ATTRIBUTION_FILE.read_text(encoding="utf-8")) or {}
        return {str(key): list(value or []) for key, value in data.items()}
    except Exception as exc:  # noqa: BLE001
        print(f"[warn] 归属覆盖表读取失败：{exc}")
        return {}


def load_results() -> dict[str, dict]:
    """Allure 结果：pytest nodeid → {status, message, uuid, name}。

    nodeid 由 conftest 写入 `pytest_nodeid` 标签（fullName 会丢参数/类名，不能直接用）；
    同一用例有重跑结果时取 start 最晚的一条。
    """
    results: dict[str, dict] = {}
    for file in ALLURE_DIR.glob("*-result.json"):
        try:
            data = json.loads(file.read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001
            continue
        labels = {l.get("name"): l.get("value") for l in (data.get("labels") or [])}
        key = labels.get("pytest_nodeid") or data.get("fullName") or ""
        if not key:
            continue
        start = (data.get("start") or 0)
        previous = results.get(key)
        if previous and previous.get("start", 0) > start:
            continue
        severity = labels.get("severity") or ""
        logs = []
        evidence = ""
        for attachment in data.get("attachments") or []:
            name = attachment.get("name") or ""
            source = attachment.get("source") or ""
            if not source or ("后端日志" not in name and name != "失败依据"):
                continue
            log_file = ALLURE_DIR / source
            if not log_file.exists():
                continue
            try:
                text = log_file.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            if name == "失败依据":
                evidence = text
            else:
                logs.append({"name": name, "text": text})
        failure = {}
        blocks = re.findall(
            r"最后请求：(\w+) (\S+)\n响应状态：HTTP (\d+)\n响应体：(.*)",
            evidence,
        )
        if blocks:
            method, path, status, body_text = next(
                (block for block in blocks if int(block[2]) >= 500), blocks[-1]
            )
            try:
                body = json.loads(body_text)
            except Exception:  # noqa: BLE001
                body = {}
            failure = {
                "method": method,
                "path": path,
                "status": int(status),
                "body": body,
                "traceId": body.get("traceId") if isinstance(body, dict) else None,
                "bizCode": body.get("code") if isinstance(body, dict) else None,
            }
        results[key] = {
            "name": data.get("name") or "",
            "status": data.get("status") or "unknown",
            "message": ((data.get("statusDetails") or {}).get("message") or "")[:300],
            "uuid": data.get("uuid") or "",
            "start": start,
            "durationMs": max(0, (data.get("stop") or start) - start),
            "priority": _SEVERITY_TO_PRIORITY.get(severity, ""),
            "logs": logs,
            "failure": failure,
        }
    return results


def load_verify_meta() -> dict[str, str]:
    """用例验证点（data/case_meta.yaml），按「文件::函数」索引。"""
    if not META_FILE.exists():
        return {}
    try:
        import yaml

        data = yaml.safe_load(META_FILE.read_text(encoding="utf-8")) or {}
        return {
            str(key): str(value.get("verify") or "")
            for key, value in (data.get("cases") or {}).items()
        }
    except Exception:  # noqa: BLE001
        return {}


def classify_dimension(name: str, file: str) -> str:
    if name.startswith("【"):
        dim = name[1:name.find("】")]
        if dim in DIMENSION_ORDER:
            return dim
    prefix_map = {
        "安全-": "安全",
        "逆向-": "功能逆向",
        "边界-": "边界值",
        "异常-": "异常",
    }
    for prefix, dim in prefix_map.items():
        if name.startswith(prefix):
            return dim
    file_map = {
        "test_security.py": "安全",
        "test_security_idor.py": "安全",
        "test_validation.py": "边界值",
        "test_validation_interactions.py": "参数组合",
        "test_idempotency.py": "幂等",
        "test_bulk_data.py": "大数据量",
        "test_invariants.py": "业务不变量",
        "test_endpoint_matrix_orders.py": "契约/其他",
        "test_data_driven.py": "边界值",
    }
    stem = Path(file).name
    if stem in file_map:
        return file_map[stem]
    lowered = name.lower()
    if any(hint in lowered or hint in name for hint in _NEGATIVE_HINTS):
        return "功能逆向"
    return "功能正向"


PRIMARY_DIMENSION_FILES = {
    "test_redis_state.py": "Redis校验",
    "test_mq_order_events.py": "MQ校验",
    "test_write_persistence.py": "DB校验",
    "test_admin_crud_roundtrip.py": "DB校验",
}
DB_DIMENSION_FILES = {
    "test_write_persistence.py",
    "test_admin_crud_roundtrip.py",
    "test_invariants.py",
}
METHOD_DIMENSION_FILES = {
    # 契约矩阵按端点方法逐项调用，是测试点级别的“请求方式”验证；
    # 巡检/普通功能用例的方法一致性仍由覆盖率页的 methodChecked 统计，避免全量打标。
    "test_endpoint_matrix_orders.py",
}


def classify_dimensions(file: str, primary: str) -> tuple[str, list[str]]:
    """返回（主分类，全部分类）。主分类用于饼图/排序，全部分类用于多标签统计。"""
    stem = Path(file).name
    primary = PRIMARY_DIMENSION_FILES.get(stem, primary)
    dimensions = [primary]
    if stem in DB_DIMENSION_FILES and "DB校验" not in dimensions:
        dimensions.append("DB校验")
    if stem in METHOD_DIMENSION_FILES:
        dimensions.append("请求方式校验")
    return primary, dimensions


def _case_name(name: str) -> str:
    """去掉参数后缀中的路径噪声，保留用例编号与标题。"""
    return re.sub(r"\s+", " ", name).strip()


# ---------------- 组装数据 ----------------

def build_data(report_dir: Path) -> dict:
    endpoints = json.loads(MODEL.read_text(encoding="utf-8"))["endpoints"]
    mapping = json.loads(MAP_FILE.read_text(encoding="utf-8")) if MAP_FILE.exists() else {
        "tests": {}, "samples": {}
    }
    results = load_results()
    risks = load_risk_map()
    testcase_ids = load_testcase_ids(report_dir)
    suite_links = load_suite_links(report_dir)
    overrides = load_attribution()
    details_map = mapping.get("details") or {}
    verify_meta = load_verify_meta()
    recorded = mapping.get("tests") or {}
    setup_calls = mapping.get("setup") or {}
    # 归属 = call 阶段调用；若用例没有 call 阶段请求（数据完全由夹具准备），
    # 退化为「setup 阶段最后一个接口」；最后叠加人工覆盖表
    attribution: dict[str, list[str]] = {nodeid: list(eps) for nodeid, eps in recorded.items()}
    setup_origin: set[str] = set()
    for nodeid, eps in setup_calls.items():
        # UI 用例（含 UI 端到端）不属于接口视图，且其登录来自 UI 夹具，不能兜底到接口
        if nodeid.startswith("tests/ui/") or "test_ui_purchase_flow" in nodeid:
            continue
        if attribution.get(nodeid) or not eps:
            continue
        attribution[nodeid] = [eps[-1]]
        setup_origin.add(nodeid)
    for nodeid, extra in overrides.items():
        attribution[nodeid] = sorted(set(attribution.get(nodeid, [])) | set(extra))
    spec = json.loads(OPENAPI.read_text(encoding="utf-8")) if OPENAPI.exists() else {}
    sampler = SchemaSampler(spec)

    opens = {}
    for path, item in (spec.get("paths") or {}).items():
        for method in ("get", "post", "put", "delete", "patch"):
            if method in item:
                opens[(method.upper(), path)] = item[method]

    def _verify_of(nodeid: str) -> str:
        base = nodeid.split("[", 1)[0]
        parts = base.split("::")
        if len(parts) >= 2:
            return verify_meta.get(f"{parts[0]}::{parts[-1]}") or ""
        return ""

    def _detail_for(details: dict, method: str, path: str) -> dict:
        direct = details.get(f"{method} {path}")
        if direct:
            return direct
        for key, value in details.items():
            key_method, _, template = key.partition(" ")
            if key_method != method:
                continue
            pattern = "^" + re.sub(r"\{[^}]+\}", "[^/]+", template) + "$"
            if re.match(pattern, path):
                return value
        return {}

    def _case_id_of(name: str) -> str:
        match = re.match(r"^((?:API|UI|E2E)-[A-Z]+-\d+)", name)
        return match.group(1) if match else ""

    tests_by_endpoint: dict[str, list] = {}
    for nodeid, eps in attribution.items():
        result = results.get(nodeid)
        if not result:
            continue
        file = nodeid.split("::")[0]
        dimension = classify_dimension(result["name"], file)
        dimension, dimensions = classify_dimensions(file, dimension)
        per_test_details = details_map.get(nodeid) or {}
        testcase_id = testcase_ids.get(result["name"])
        for ep in eps:
            detail = per_test_details.get(ep) or {}
            priority = "P2" if dimension == "异常" else (result.get("priority") or "")
            backend_log = ""
            backend_log_source = ""
            response_detail = detail.get("response") or {}
            body = response_detail.get("body")
            failure = result.get("failure") or {}
            trace_id = (failure.get("traceId") if failure else None) or (
                body.get("traceId") if isinstance(body, dict) else None
            )
            biz_code = (failure.get("bizCode") if failure else None) or (
                body.get("code") if isinstance(body, dict) else None
            )
            http_status = int(failure.get("status") or response_detail.get("status") or 0)
            failure_request = {}
            if failure:
                failure_request = _detail_for(
                    per_test_details, failure.get("method") or "", failure.get("path") or ""
                ).get("request") or {}
            if result["status"] in ("failed", "broken"):
                # 优先用 Allure 附件里的后端日志（与 Allure 页面同源）
                attachment_logs = result.get("logs") or []
                chosen = next((item for item in attachment_logs if "500" in item["name"]), None)
                if chosen and not ("Unhandled error" in chosen["text"] or "Exception" in chosen["text"]):
                    chosen = None
                usable = [
                    item for item in attachment_logs
                    if not item["text"].lstrip().startswith("未找到 traceId=")
                ]
                errors = [
                    item for item in usable
                    if "Unhandled error" in item["text"] or "Exception" in item["text"]
                ]
                if chosen is None and errors:
                    chosen = max(errors, key=lambda item: len(item["text"]))
                elif chosen is None and trace_id:
                    chosen = next(
                        (item for item in usable if str(trace_id) in item["text"]),
                        None,
                    )
                if chosen is None:
                    chosen = max(usable, key=lambda item: len(item["text"]), default=None)
                if chosen and trace_id and not (
                    "Unhandled error" in chosen["text"] or "Exception" in chosen["text"]
                ):
                    try:
                        from helpers.diagnose import trace_excerpt

                        direct = trace_excerpt(str(trace_id))
                    except Exception:  # noqa: BLE001
                        direct = ""
                    if "Unhandled error" in direct or "Exception" in direct:
                        chosen = {"name": "后端日志（运行时回退）", "text": direct}
                if chosen:
                    backend_log = chosen["text"]
                    backend_log_source = "allure:" + chosen["name"]
                elif trace_id and http_status >= 500:
                    try:
                        from helpers.diagnose import trace_excerpt

                        backend_log = trace_excerpt(str(trace_id))[:12000]
                        backend_log_source = "backend-log"
                    except Exception:  # noqa: BLE001
                        backend_log = ""
            tests_by_endpoint.setdefault(ep, []).append(
                {
                    "name": _case_name(result["name"]),
                    "caseId": _case_id_of(result["name"]),
                    "nodeid": nodeid,
                    "file": file,
                    "status": result["status"],
                    "message": result["message"],
                    "uuid": result["uuid"],
                    "priority": priority,
                    "durationMs": result.get("durationMs") or 0,
                    "verify": _verify_of(nodeid),
                    "request": detail.get("request") or {},
                    "response": response_detail,
                    "backendLog": backend_log,
                    "backendLogSource": backend_log_source,
                    "httpStatus": http_status,
                    "bizCode": biz_code,
                    "traceId": trace_id or "",
                    "failureMethod": failure.get("method", ""),
                    "failurePath": failure.get("path", ""),
                    "failureResponse": (
                        {"status": failure.get("status"), "body": failure.get("body")}
                        if failure else {}
                    ),
                    "failureRequest": failure_request,
                    "dimensions": dimensions,
                    "testCaseId": testcase_id or "",
                    "link": (
                        "../index.html#/suites/" + suite_links[testcase_id] + "/"
                        if testcase_id and testcase_id in suite_links
                        else ""
                    ),
                    "searchLink": (
                        "../index.html#/suites?search=" + quote(result["name"])
                    ),
                    "dimension": dimension,
                    "origin": "setup" if nodeid in setup_origin else "call",
                }
            )

    interfaces = []
    for ep in endpoints:
        method, path = ep["method"], ep["path"]
        key = f"{method} {path}"
        op = opens.get((method, path)) or {}
        params = _mask(_param_example(op, sampler)) if op else {}
        params_source = "OpenAPI 示例" if params else ""
        if not params:
            body_keys = ep.get("body_keys") or []
            params = {"body": {k: "string" for k in body_keys}} if body_keys else {}
            params_source = "字段骨架" if body_keys else ""
        sample = (mapping.get("samples") or {}).get(key)
        if sample and isinstance(sample.get("body"), dict):
            payload = sample["body"]
            response = payload.get("data", payload)
            response_source = "真实响应样本"
        else:
            response = _response_example(op, sampler)
            response_source = "OpenAPI 示例" if response is not None else ""
            if response is None:
                resp_keys = ep.get("resp_keys") or []
                if resp_keys:
                    response = {k: "string" for k in resp_keys}
                    response_source = "字段骨架"
                else:
                    response = {"_note": "测试未以合法参数调用该接口，暂未采集到响应"}
                    response_source = "占位说明"
        tests = tests_by_endpoint.get(key, [])
        tests.sort(key=lambda t: (DIMENSION_ORDER.index(t["dimension"])
                                  if t["dimension"] in DIMENSION_ORDER else 99, t["name"]))
        group_stats = OrderedDict()
        for test in tests:
            for dim in test.get("dimensions") or [test["dimension"]]:
                group = group_stats.setdefault(dim, {"total": 0, "failed": 0})
                group["total"] += 1
                if test["status"] in ("failed", "broken"):
                    group["failed"] += 1
        interfaces.append(
            {
                "id": key,
                "name": op.get("summary") or ep.get("summary") or path,
                "method": method,
                "path": path,
                "auth": ep.get("auth") or "",
                "risk": risks.get((method, path), "P2"),
                "params": params,
                "paramsSource": params_source,
                "response": response,
                "responseSource": response_source,
                "stats": {
                    "total": len(tests),
                    "failed": sum(1 for t in tests if t["status"] in ("failed", "broken")),
                    "dimensions": list(group_stats.keys()),
                },
                "testPoints": tests,
            }
        )

    risk_order = {"P0": 0, "P1": 1, "P2": 2}
    interfaces.sort(
        key=lambda item: (risk_order.get(item["risk"], 9), item["path"], item["method"])
    )
    dimension_stats: dict[str, dict] = {}
    primary_stats: dict[str, dict] = {}
    seen_node_dims: set[tuple[str, str]] = set()
    seen_primary: set[str] = set()
    for tests in tests_by_endpoint.values():
        for test in tests:
            status = test["status"]
            for dim in test.get("dimensions") or [test["dimension"]]:
                key = (test["nodeid"], dim)
                if key in seen_node_dims:
                    continue
                seen_node_dims.add(key)
                bucket = dimension_stats.setdefault(
                    dim, {"dimension": dim, "total": 0, "passed": 0,
                          "failed": 0, "skipped": 0, "broken": 0}
                )
                bucket["total"] += 1
                if status in bucket:
                    bucket[status] += 1
                elif status == "unknown":
                    bucket["skipped"] += 1
            primary = test["dimension"]
            if test["nodeid"] in seen_primary:
                continue
            seen_primary.add(test["nodeid"])
            bucket = primary_stats.setdefault(
                primary, {"dimension": primary, "total": 0, "passed": 0,
                          "failed": 0, "skipped": 0, "broken": 0}
            )
            bucket["total"] += 1
            if status in bucket:
                bucket[status] += 1
            elif status == "unknown":
                bucket["skipped"] += 1

    ordered_dims = sorted(
        dimension_stats.values(),
        key=lambda d: (DIMENSION_ORDER.index(d["dimension"]) if d["dimension"] in DIMENSION_ORDER else 99),
    )
    ordered_primary = sorted(
        primary_stats.values(),
        key=lambda d: (DIMENSION_ORDER.index(d["dimension"]) if d["dimension"] in DIMENSION_ORDER else 99),
    )

    mapped_nodeids = {t["nodeid"] for tests in tests_by_endpoint.values() for t in tests}
    all_nodeids = set(results)
    ui_nodeids = {
        nodeid
        for nodeid in all_nodeids
        if nodeid.startswith("tests/ui/") or "test_ui_purchase_flow" in nodeid
    }
    unattributed_nodeids = sorted(all_nodeids - mapped_nodeids - ui_nodeids)
    unattributed = []
    for nodeid in unattributed_nodeids:
        result = results[nodeid]
        testcase_id = testcase_ids.get(result["name"])
        unattributed.append(
            {
                "name": result["name"],
                "status": result["status"],
                "file": nodeid.split("::")[0],
                "link": (
                    "../index.html#/suites/" + suite_links[testcase_id] + "/"
                    if testcase_id and testcase_id in suite_links
                    else ""
                ),
            }
        )
    failed_nodeids = {
        t["nodeid"]
        for tests in tests_by_endpoint.values()
        for t in tests
        if t["status"] in ("failed", "broken")
    }
    return {
        "generatedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "summary": {
            "interfaces": len(interfaces),
            "covered": sum(1 for i in interfaces if i["stats"]["total"]),
            "tests": len(mapped_nodeids),
            "apiTests": len(mapped_nodeids - ui_nodeids),
            "failedTests": len(failed_nodeids),
            "reportTotal": len(all_nodeids),
            "uiTests": len(ui_nodeids),
            "unattributed": len(unattributed),
        },
        "unattributedTests": unattributed,
        "dimensionStats": ordered_dims,
        "primaryDimensionStats": ordered_primary,
        "multiDimension": True,
        "interfaces": interfaces,
    }


# ---------------- 并行对比数据 ----------------

def _primary_method_map() -> dict[str, str]:
    """nodeid → 主体接口的 HTTP 方法（用于「方法耗时构成」辅助统计）。"""
    mapping_file = ROOT / "artifacts" / "case_endpoint_map.json"
    result: dict[str, str] = {}
    try:
        data = json.loads(mapping_file.read_text(encoding="utf-8"))
        for nodeid, endpoints in (data.get("tests") or {}).items():
            if endpoints:
                result[nodeid] = endpoints[0].split(" ", 1)[0]
    except Exception:  # noqa: BLE001
        pass
    return result


def _run_counts(run_dir: Path) -> dict:
    """从 results.json 去重统计通过/失败/跳过（重跑取最后一次）。"""
    files = sorted(run_dir.glob("results*.json"))
    if not files:
        return {}
    latest: dict[str, str] = {}
    for file in files:
        try:
            payload = json.loads(file.read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001
            continue
        for record in payload.get("results") or []:
            latest[record.get("nodeid", "")] = record.get("outcome", "")
    counts = {"passed": 0, "failed": 0, "skipped": 0}
    for outcome in latest.values():
        if outcome in counts:
            counts[outcome] += 1
    return counts


def _method_mix(run_dir: Path, methods: dict[str, str]) -> dict:
    """按主体接口方法汇总用例耗时（辅助口径：单测 duration 求和，非墙钟）。"""
    results_file = run_dir / "results.json"
    if not results_file.exists() or not methods:
        return {}
    try:
        payload = json.loads(results_file.read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001
        return {}
    latest: dict[str, dict] = {}
    for record in payload.get("results") or []:
        latest[record.get("nodeid", "")] = record
    mix: dict[str, dict] = {}
    for nodeid, record in latest.items():
        method = methods.get(nodeid)
        if not method:
            continue
        bucket = mix.setdefault(method, {"count": 0, "seconds": 0.0})
        bucket["count"] += 1
        bucket["seconds"] += float(record.get("duration_seconds") or 0)
    for bucket in mix.values():
        bucket["seconds"] = round(bucket["seconds"], 2)
    return mix


def build_benchmark_data() -> dict:
    """汇总 API / UI 两层的串行与并行基准，以及 API 方法耗时构成。"""
    root = ROOT / "artifacts" / "parallel"
    methods = _primary_method_map()
    layers: dict[str, dict] = {"api": {"runs": []}, "ui": {"runs": []}}
    for folder in sorted(root.glob("*")):
        summary_file = folder / "run-summary.json"
        if not summary_file.exists():
            continue
        try:
            item = {"dir": folder.name, **json.loads(summary_file.read_text(encoding="utf-8"))}
        except Exception:  # noqa: BLE001
            continue
        item.setdefault("layer", "api")
        item.setdefault("mode", "serial" if item.get("workers") == 1 else "parallel")
        if item["layer"] not in layers:
            continue
        computed = _run_counts(folder)
        if computed:
            item.update(computed)
        if item["mode"] == "serial":
            layers[item["layer"]]["serial"] = item
        else:
            layers[item["layer"]]["runs"].append(item)

    for name, layer in layers.items():
        runs = sorted(layer.get("runs") or [], key=lambda r: r["dir"])
        if name == "api":
            # 正式口径：每个接口独立并发（--dist loadgroup）；worksteal 仅作历史参考
            grouped = [r for r in runs if r.get("dist") == "loadgroup"]
            runs = grouped or runs
        layer["runs"] = runs[-6:]
        layer["referenceRuns"] = [
            r for r in sorted(layer.get("runs") or [], key=lambda x: x["dir"])
            if name == "api" and r.get("dist") != "loadgroup"
        ]
        totals = sorted(int(r.get("total_seconds") or 0) for r in layer["runs"] if r.get("total_seconds"))
        layer["median_seconds"] = totals[len(totals) // 2] if totals else None
        serial = layer.get("serial") or {}
        serial_total = int(serial.get("total_seconds") or 0)
        layer["speedup"] = (
            round(serial_total / layer["median_seconds"], 2)
            if serial_total and layer.get("median_seconds") else None
        )

    api = layers["api"]
    serial_dir = api.get("serial", {}).get("dir")
    if serial_dir:
        api["methodMix"] = {"serial": _method_mix(root / serial_dir, methods)}
    parallel_runs = [r for r in api["runs"] if r.get("total_seconds")]
    if parallel_runs:
        median_run = min(parallel_runs, key=lambda r: abs(int(r["total_seconds"]) - (api.get("median_seconds") or 0)))
        api["methodMix"] = api.get("methodMix") or {}
        api["methodMix"]["parallel"] = _method_mix(root / median_run["dir"], methods)
        api["methodMix"]["parallelRun"] = median_run["dir"]

    return {"generatedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"), "layers": layers}


# ---------------- 落盘与入口注入 ----------------

def _copy_shell(report_dir: Path) -> None:
    ext = report_dir / "ext"
    assets = ext / "assets"
    assets.mkdir(parents=True, exist_ok=True)
    for name in ("index.html", "app.css", "app.js"):
        shutil.copyfile(SHELL_DIR / name, ext / name if name == "index.html" else assets / name)


def _inject_entry(report_dir: Path) -> None:
    index = report_dir / "index.html"
    if not index.exists():
        return
    html = index.read_text(encoding="utf-8")
    marker = "zcode-report-entry"
    if marker in html:
        html = re.sub(r"\s*<!--zcode-entry-->.*?<!--/zcode-entry-->", "", html, flags=re.S)
    snippet = """
<!--zcode-entry--><a id="zcode-report-entry" href="ext/index.html#/api/list" title="接口视图：按接口查看参数/返回值示例与测试点"
 style="position:fixed;right:24px;bottom:24px;z-index:9999;padding:10px 18px;border-radius:999px;
 background:#4b6bfb;color:#fff;font:600 14px/1.2 -apple-system,'PingFang SC','Microsoft YaHei',sans-serif;
 box-shadow:0 8px 24px rgba(75,107,251,.35);text-decoration:none;display:inline-flex;align-items:center;gap:6px;">
 📋 接口视图</a>
<style id="zcode-trace-style">
.attachment-row[data-type="application/vnd.allure.playwright-trace"] { cursor: pointer; }
.attachment-row[data-type="application/vnd.allure.playwright-trace"] .attachment-row__name { color: #4b6bfb; font-weight: 600; }
</style>
<script id="zcode-trace-click">
(function () {
  document.addEventListener("mouseover", function (event) {
    var row = event.target.closest &&
      event.target.closest('.attachment-row[data-type="application/vnd.allure.playwright-trace"]');
    if (row && !row.title) row.title = "点击直接查看 Playwright trace（右侧图标可下载）";
  }, true);
  // Playwright trace 附件：默认只有右侧全屏图标能打开内嵌查看器。
  // 这里让「整行可点击」直接打开，同时保留下载/全屏按钮的原行为。
  document.addEventListener("click", function (event) {
    var row = event.target.closest &&
      event.target.closest('.attachment-row[data-type="application/vnd.allure.playwright-trace"]');
    if (!row) return;
    if (event.target.closest("a, button, .attachment-row__control")) return;
    var fullscreen = row.querySelector(".attachment-row__fullscreen a");
    if (fullscreen) {
      fullscreen.click();
      event.preventDefault();
      event.stopPropagation();
    }
  }, true);
})();
</script><!--/zcode-entry-->
"""
    html = html.replace("</body>", snippet + "</body>")
    index.write_text(html, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report-dir", default=str(ROOT / "artifacts" / "allure-report"))
    args = parser.parse_args()
    report_dir = Path(args.report_dir)
    if not report_dir.exists():
        raise SystemExit(f"报告目录不存在：{report_dir}（先执行 allure generate）")

    data = build_data(report_dir)
    _copy_shell(report_dir)
    ext = report_dir / "ext"
    (ext / "data").mkdir(parents=True, exist_ok=True)
    (ext / "data" / "api-matrix.json").write_text(
        json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8"
    )
    (ext / "data" / "benchmark.json").write_text(
        json.dumps(build_benchmark_data(), ensure_ascii=False, indent=1), encoding="utf-8"
    )
    enhancements_file = ROOT / "data" / "report_enhancements.json"
    if enhancements_file.exists():
        (ext / "data" / "enhancements.json").write_text(
            enhancements_file.read_text(encoding="utf-8"), encoding="utf-8"
        )
    # 两级导航：大类（接口 / UI …）→ 视图（列表 / 统计 …）
    groups = [
        {
            "id": "api",
            "title": "接口自动化",
            "views": [
                {"id": "list", "title": "接口列表", "type": "api", "data": "data/api-matrix.json"},
                {"id": "stats", "title": "分类统计", "type": "stats", "data": "data/api-matrix.json"},
                {"id": "benchmark", "title": "并行对比", "type": "benchmark", "data": "data/benchmark.json"},
                {"id": "coverage", "title": "覆盖率", "type": "coverage", "data": "data/coverage.json"},
                {"id": "defects", "title": "缺陷", "type": "defects", "data": "data/defects.json"},
                {"id": "history", "title": "用例历史", "type": "history", "data": "data/history.json"},
            ],
        },
        {
            "id": "ui",
            "title": "UI 自动化",
            "views": [
                {"id": "overview", "title": "浏览器矩阵", "type": "ui", "data": "data/ui.json"},
            ],
        },
    ]
    (ext / "manifest.json").write_text(
        json.dumps(
            {"version": datetime.now().strftime("%Y%m%d%H%M%S"), "groups": groups},
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    _inject_entry(report_dir)
    print(
        f"[done] 接口视图：{len(data['interfaces'])} 个接口，"
        f"{data['summary']['covered']} 个有测试点，"
        f"{data['summary']['tests']} 条用例，{data['summary']['failedTests']} 条失败"
    )


if __name__ == "__main__":
    main()
