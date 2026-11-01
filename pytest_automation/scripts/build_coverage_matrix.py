#!/usr/bin/env python3
"""静态覆盖率矩阵：接口 × 用例 × 代码链路 × 写接口 DB 断言 × 请求方式校验。

输入：ext/data/api-matrix.json（上一步生成）、endpoint-model、call-graph、write-contracts。
输出：ext/data/coverage.json（供「覆盖率」子 tab 使用）。
"""
from __future__ import annotations

import json
import re
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPO = ROOT.parent


def load(path: Path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001
        return default


REDIS_ENDPOINTS = {
    "POST /admin/auth/login", "POST /admin/auth/logout", "POST /admin/auth/refresh",
    "POST /api/auth/login", "POST /api/auth/phone", "POST /api/auth/logout",
    "POST /api/auth/refresh", "POST /api/auth/sms/send",
}
MQ_ENDPOINTS = {"POST /api/payment/sandbox"}

REDIS_MARKERS = ("RedisTemplate", "StringRedisTemplate", "TokenBlacklist", "redisTemplate")
MQ_MARKERS = ("RabbitTemplate", "AmqpTemplate", "@RabbitListener", "OrderPaidEventPublisher")


def component_index() -> dict[str, set[str]]:
    index: dict[str, set[str]] = {}
    for file in (REPO / "backend").rglob("*.java"):
        try:
            text = file.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        flags = set()
        if any(marker in text for marker in REDIS_MARKERS):
            flags.add("Redis")
        if any(marker in text for marker in MQ_MARKERS):
            flags.add("MQ")
        if flags:
            index[file.stem] = flags
    return index


def db_exemption(path: str) -> str:
    if path in ("/admin/auth/login", "/admin/auth/logout", "/admin/auth/refresh",
                "/api/auth/login", "/api/auth/logout", "/api/auth/refresh",
                "/api/auth/phone", "/api/auth/sms/send"):
        return "认证/令牌接口，核心状态在 Redis，无业务表落库断言"
    if path.endswith("/files/presign"):
        return "仅签发上传地址，不写业务表"
    return ""



FIELD_RE = re.compile(r"(?:private|protected|public)\s+(?:final\s+)?([A-Z][\w]*(?:<[^>]+>)?)\s+(\w+)\s*;")
METHOD_RE = re.compile(
    r"(?:public|protected|private)\s+(?:static\s+)?[\w<>\[\], ?]+\s+(\w+)\s*\([^;{]*\)\s*(?:throws [^{]+)?\{"
)
CALL_RE = re.compile(r"\b(\w+)\.(\w+)\s*\(")


def load_jacoco_methods() -> dict[tuple[str, str], dict]:
    path = ROOT / "artifacts" / "jacoco" / "jacoco.xml"
    if not path.exists():
        return {}
    try:
        root = ET.parse(path).getroot()
    except Exception:  # noqa: BLE001
        return {}
    result = {}
    for cls in root.iter("class"):
        simple = cls.get("name", "").split("/")[-1]
        for method in cls.findall("method"):
            line = next((c for c in method.findall("counter") if c.get("type") == "LINE"), None)
            if line is None:
                continue
            covered, missed = int(line.get("covered")), int(line.get("missed"))
            result[(simple, method.get("name"))] = {
                "lineCovered": covered, "lineMissed": missed,
                "status": "covered" if covered and not missed else
                          "partial" if covered else "uncovered",
            }
    return result


def load_jacoco_classes() -> dict[str, dict]:
    path = ROOT / "artifacts" / "jacoco" / "jacoco.xml"
    if not path.exists():
        return {}
    try:
        root = ET.parse(path).getroot()
    except Exception:  # noqa: BLE001
        return {}
    line_map = {}
    for source in root.iter("sourcefile"):
        per_line = {}
        for line in source.findall("line"):
            ci, mi = int(line.get("ci")), int(line.get("mi"))
            per_line[int(line.get("nr"))] = {
                "covered": ci > 0, "missed": mi > 0,
                "status": "covered" if ci and not mi else "partial" if ci else "uncovered",
            }
        line_map[source.get("name")] = per_line
    result = {}
    for cls in root.iter("class"):
        full = cls.get("name", "")
        if not full.startswith("com/shangpivot/"):
            continue
        package, _, simple = full.rpartition("/")
        source_name = simple.split("$", 1)[0] + ".java"
        methods = []
        for method in cls.findall("method"):
            name = method.get("name")
            if name in ("<init>", "<clinit>"):
                continue
            line = next((c for c in method.findall("counter") if c.get("type") == "LINE"), None)
            if line is None:
                continue
            covered, missed = int(line.get("covered")), int(line.get("missed"))
            methods.append({
                "name": name, "lineCovered": covered, "lineMissed": missed,
                "status": "covered" if covered and not missed else
                          "partial" if covered else "uncovered",
            })
        result[simple] = {
            "package": package.replace("/", "."),
            "html": f"jacoco/html/{package}/{simple}.html",
            "methods": sorted(methods, key=lambda item: (-item["lineMissed"], item["name"])),
            "_lines": line_map.get(source_name, {}),
        }
    return result


def enrich_class_lines(classes: dict[str, dict], node_map: dict[str, dict]) -> None:
    for cls, info in classes.items():
        source = source_file(cls, node_map)
        if not source:
            continue
        try:
            lines = source.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        line_status = info.pop("_lines", {})
        info["source"] = str(source.relative_to(REPO))
        for method in info.get("methods") or []:
            body, start = method_body(source, method["name"])
            if not start:
                continue
            end = start + body.count("\n")
            method["startLine"], method["endLine"] = start, end
            method["lines"] = [
                {
                    "nr": nr,
                    "code": lines[nr - 1].rstrip() if 0 < nr <= len(lines) else "",
                    **line_status.get(nr, {"status": "unknown"}),
                }
                for nr in range(start, min(end, len(lines)) + 1)
            ]


def graph_node_map(call_graph: dict) -> dict[str, dict]:
    return {node.get("label"): node for node in call_graph.get("nodes") or []}


def source_file(class_name: str, node_map: dict[str, dict]) -> Path | None:
    node = node_map.get(class_name) or {}
    if node.get("file"):
        path = REPO / node["file"]
        if path.exists():
            return path
    hits = list((REPO / "backend").glob(f"**/src/main/java/**/{class_name}.java"))
    return hits[0] if hits else None


def fields_of(path: Path) -> dict[str, str]:
    text = path.read_text(encoding="utf-8", errors="replace")
    return {name: kind for kind, name in FIELD_RE.findall(text)}


def method_body(path: Path, method: str) -> tuple[str, int]:
    text = path.read_text(encoding="utf-8", errors="replace")
    match = next((m for m in METHOD_RE.finditer(text) if m.group(1) == method), None)
    if not match:
        return "", 0
    start = match.end() - 1
    depth = 0
    for index in range(start, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return text[start:index + 1], text[:match.start()].count("\n") + 1
    return "", 0


def call_nodes(path: Path, body: str, node_map: dict[str, dict],
               jacoco: dict, depth: int = 0, seen: set | None = None) -> list[dict]:
    if depth > 2:
        return []
    seen = seen or set()
    fields = fields_of(path)
    nodes = []
    for var, method in CALL_RE.findall(body):
        cls = fields.get(var)
        if not cls or cls == "this":
            continue
        cls = cls.split("<", 1)[0]
        key = f"{cls}#{method}"
        if key in seen or key == f"{path.stem}#{method}":
            continue
        seen.add(key)
        node = node_map.get(cls) or {}
        kind = node.get("kind", "")
        ntype = {"mapper": "mapper", "service": "service",
                 "cross_api": "cross_api"}.get(kind, "service")
        cov = jacoco.get((cls, method), {})
        target = source_file(cls, node_map)
        line = 0
        if target and kind != "mapper":
            _, line = method_body(target, method)
        nodes.append({
            "type": ntype, "name": cls, "method": method, "label": key,
            "file": str(target.relative_to(REPO)) if target else "",
            "line": line, "coverage": cov,
        })
        if kind == "mapper":
            for edge in call_graph_edges:
                if edge.get("from") == f"mapper:{cls}" and edge.get("kind") == "accesses":
                    nodes.append({"type": "table", "name": edge.get("to", "").split(":", 1)[-1],
                                  "label": edge.get("to", "").split(":", 1)[-1], "coverage": {}})
        elif target and kind == "service":
            body2, _ = method_body(target, method)
            if body2:
                nodes.extend(call_nodes(target, body2, node_map, jacoco, depth + 1, seen))
    return nodes


def _ranges(numbers: list[int]) -> str:
    if not numbers:
        return ""
    groups, start, prev = [], numbers[0], numbers[0]
    for number in numbers[1:]:
        if number == prev + 1:
            prev = number
            continue
        groups.append(f"{start}" if start == prev else f"{start}-{prev}")
        start = prev = number
    groups.append(f"{start}" if start == prev else f"{start}-{prev}")
    return ",".join(groups)


def concrete_suggestions(nodes: list[dict], classes: dict[str, dict]) -> list[str]:
    suggestions = []
    for node in nodes:
        if not node.get("method"):
            continue
        cov = node.get("coverage") or {}
        if cov.get("status") not in ("partial", "uncovered"):
            continue
        info = classes.get(node.get("name")) or {}
        method = next((m for m in info.get("methods") or []
                       if m.get("name") == node.get("method")), None)
        lines = [line for line in (method or {}).get("lines") or []
                 if line.get("status") == "uncovered"]
        if lines:
            numbers = [line["nr"] for line in lines]
            snippets = []
            for line in lines[:3]:
                code = (line.get("code") or "").strip()
                if code and code not in snippets:
                    snippets.append(code[:60])
            snippet = " / ".join(snippets[:2])
            suggestions.append(
                f"补充 {node['label']} 第 {_ranges(numbers)} 行的未覆盖场景"
                + (f"（{snippet}）" if snippet else "")
            )
        else:
            suggestions.append(
                f"补充 {node['label']} 的未覆盖分支/异常用例"
                f"（当前行覆盖 {cov.get('lineCovered', 0)}/"
                f"{(cov.get('lineCovered', 0) + cov.get('lineMissed', 0))}）"
            )
    return suggestions


def method_chain(endpoint: dict, call_graph: dict, node_map: dict[str, dict],
                 jacoco: dict, call_edges: dict) -> tuple[list[dict], list[dict]]:
    chain = []
    controller = endpoint.get("controller")
    method = endpoint.get("method")
    ctrl_node = node_map.get(controller) or {}
    ctrl_file = source_file(controller, node_map) if controller else None
    ctrl_line = 0
    if ctrl_file and method:
        _, ctrl_line = method_body(ctrl_file, method)
    chain.append({
        "type": "controller", "name": controller, "method": method,
        "label": f"{controller}#{method}", "file": str(ctrl_file.relative_to(REPO)) if ctrl_file else "",
        "line": ctrl_line, "coverage": jacoco.get((controller, method), {}),
    })
    for service, svc_method in call_edges.get(endpoint.get("endpoint"), []):
        target = source_file(service, node_map)
        line = 0
        if target:
            _, line = method_body(target, svc_method)
        chain.append({
            "type": "service", "name": service, "method": svc_method,
            "label": f"{service}#{svc_method}",
            "file": str(target.relative_to(REPO)) if target else "", "line": line,
            "coverage": jacoco.get((service, svc_method), {}),
        })
        if target:
            body, _ = method_body(target, svc_method)
            if body:
                chain.extend(call_nodes(target, body, node_map, jacoco, 1))
    for table in endpoint.get("tables") or []:
        chain.append({"type": "table", "name": table, "label": table, "coverage": {}})
    # 去重，保持入口→底层顺序
    unique = []
    labels = set()
    for node in chain:
        label = node.get("label") or node.get("name")
        if label in labels:
            continue
        labels.add(label)
        unique.append(node)
    methods = [node for node in unique if node.get("method")]
    suggestions = []
    for node in methods:
        cov = node.get("coverage") or {}
        if cov.get("status") in ("uncovered", "partial"):
            suggestions.append(f"补充 {node['label']} 的未覆盖分支/异常用例")
    return unique, suggestions


def main() -> None:
    global call_graph_edges
    report_dir = ROOT / "artifacts" / "allure-report"
    matrix = load(report_dir / "ext" / "data" / "api-matrix.json", {})
    call_graph = load(REPO / "api_testcases" / "baselines" / "810986c" / "call-graph.json", {})
    contracts = (load(ROOT / "artifacts" / "write-contracts.json", {}) or {}).get("endpoints") or {}

    chains = {item.get("endpoint"): item for item in call_graph.get("endpoints") or []}
    component_map = component_index()
    node_map = graph_node_map(call_graph)
    jacoco_methods = load_jacoco_methods()
    jacoco_classes = load_jacoco_classes()
    enrich_class_lines(jacoco_classes, node_map)
    call_edges: dict[str, list[tuple[str, str]]] = {}
    for edge in call_graph.get("edges") or []:
        if edge.get("kind") != "calls" or not edge.get("from", "").startswith("ep:"):
            continue
        detail = edge.get("detail") or ""
        service = edge.get("to", "").split(":", 1)[-1]
        method = detail.split(".", 1)[-1] if "." in detail else ""
        if service and method:
            call_edges.setdefault(edge["from"][3:], []).append((service, method))
    call_graph_edges = call_graph.get("edges") or []
    rows = []
    chain_nodes_total = set()
    chain_nodes_covered = set()
    write_total = write_covered = method_checked = 0
    db_not_applicable = db_missing = 0
    component_total = component_covered = 0
    method_total = method_covered = 0

    for item in matrix.get("interfaces") or []:
        endpoint_id = item.get("id")
        chain = chains.get(endpoint_id) or {}
        nodes = []
        if chain.get("controller"):
            nodes.append(chain["controller"])
        nodes += chain.get("services") or []
        nodes += chain.get("cross_api") or []
        nodes += chain.get("tables") or []
        tests = int((item.get("stats") or {}).get("total") or 0)
        is_write = item.get("method") in ("POST", "PUT", "DELETE")
        db_checked = bool(is_write and (contracts.get(endpoint_id) or {}).get("tests"))
        exemption = db_exemption(item.get("path") or "")
        if not is_write:
            db_status, db_reason = "n/a", ""
        elif db_checked:
            db_status, db_reason = "covered", "存在请求→DB 行断言"
        elif exemption:
            db_status, db_reason = "not_applicable", exemption
            db_not_applicable += 1
        else:
            db_status, db_reason = "missing", "用例已触达接口，但未做落库断言（待补）"
            db_missing += 1
        method_ok = tests > 0 and item.get("method") in ("GET", "POST", "PUT", "DELETE")
        # 组件维度按实际代码路径标注，避免同一 Controller/Service 的其它方法被误伤
        component_flags = set()
        if endpoint_id in REDIS_ENDPOINTS:
            component_flags.add("Redis")
        if endpoint_id in MQ_ENDPOINTS:
            component_flags.add("MQ")
        dimensions = (item.get("stats") or {}).get("dimensions") or []
        component_status = []
        for comp in ("Redis", "MQ"):
            if comp not in component_flags:
                continue
            checked = f"{comp}校验" in dimensions
            component_total += 1
            component_covered += 1 if checked else 0
            component_status.append({"name": comp, "checked": checked})
        chain_nodes, _ = method_chain(chain, call_graph, node_map, jacoco_methods, call_edges)
        suggestions = concrete_suggestions(chain_nodes, jacoco_classes)
        methods = [node for node in chain_nodes if node.get("method")]
        covered_methods = sum(1 for node in methods
                              if (node.get("coverage") or {}).get("status") in ("covered", "partial"))
        method_total += len(methods)
        method_covered += covered_methods
        chain_nodes_total.update(f"{endpoint_id}:{node}" for node in nodes)
        if tests:
            chain_nodes_covered.update(f"{endpoint_id}:{node}" for node in nodes)
        if is_write:
            write_total += 1
            write_covered += 1 if db_checked else 0
        method_checked += 1 if method_ok else 0
        rows.append(
            {
                "id": endpoint_id,
                "name": item.get("name"),
                "method": item.get("method"),
                "path": item.get("path"),
                "risk": item.get("risk"),
                "tests": tests,
                "dimensions": dimensions,
                "chainNodes": len(nodes),
                "chainCovered": len(nodes) if tests else 0,
                "chain": chain_nodes,
                "methodTotal": len(methods),
                "methodCovered": covered_methods,
                "suggestions": suggestions,
                "dbChecked": db_checked,
                "dbStatus": db_status,
                "dbReason": db_reason,
                "components": component_status,
                "methodChecked": method_ok,
            }
        )

    rows.sort(key=lambda r: (r["risk"] or "P2", r["path"] or ""))
    total = len(rows)
    with_tests = sum(1 for r in rows if r["tests"])
    payload = {
        "generatedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "summary": {
            "interfaces": total,
            "withTests": with_tests,
            "interfaceRate": round(with_tests / total * 100, 1) if total else 0,
            "methodChecked": method_checked,
            "writeTotal": write_total,
            "writeDbCovered": write_covered,
            "writeApplicable": write_total - db_not_applicable,
            "writeDbRate": round(
                write_covered / (write_total - db_not_applicable) * 100, 1
            ) if write_total > db_not_applicable else 0,
            "dbNotApplicable": db_not_applicable,
            "dbMissing": db_missing,
            "componentTotal": component_total,
            "componentCovered": component_covered,
            "methodTotal": method_total,
            "methodCovered": method_covered,
            "methodRate": round(method_covered / method_total * 100, 1) if method_total else 0,
            "chainNodes": len(chain_nodes_total),
            "chainNodesCovered": len(chain_nodes_covered),
            "chainRate": round(len(chain_nodes_covered) / len(chain_nodes_total) * 100, 1)
            if chain_nodes_total else 0,
        },
        "interfaces": rows,
        "jacocoClasses": jacoco_classes,
    }
    jacoco_path = ROOT / "artifacts" / "jacoco" / "summary.json"
    if jacoco_path.exists():
        runtime = json.loads(jacoco_path.read_text(encoding="utf-8"))
        payload["runtime"] = {
            "lineRate": runtime.get("line_rate"),
            "lineCovered": runtime.get("line_covered"),
            "lineMissed": runtime.get("line_missed"),
            "generatedAt": runtime.get("generatedAt"),
            "html": "jacoco/html/index.html",
            "packages": [
                {"name": name, "html": f"jacoco/html/{name}/index.html", **values}
                for name, values in (runtime.get("packages") or {}).items()
            ],
        }
    out = report_dir / "ext" / "data" / "coverage.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8")
    print(
        f"[done] 覆盖率矩阵：{with_tests}/{total} 接口有用例，"
        f"写接口 DB 断言 {write_covered}/{write_total}，链路节点 "
        f"{len(chain_nodes_covered)}/{len(chain_nodes_total)}"
    )


if __name__ == "__main__":
    main()
