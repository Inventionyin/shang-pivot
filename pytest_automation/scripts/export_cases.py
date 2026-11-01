#!/usr/bin/env python3
"""导出自动化测试用例库。

从 pytest 实际收集结果生成，保证「文档里的条数 = 真正会被执行的条数」：

    python3 scripts/export_cases.py

产物（均在 pytest_automation/ 下）：
- ``test_cases.md``   人读清单（按类型/模块分组）
- ``test_cases.csv``  机读清单（Excel 可直接打开）
- ``test_cases.json`` 完整结构化数据（含 nodeid、marker、参数、脚本位置）

用例标题/验证点维护在 ``data/case_meta.yaml``；新增用例后补一条元数据再重跑本脚本。
"""
from __future__ import annotations

import csv
import json
import sys
from collections import OrderedDict
from datetime import datetime
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parent.parent
META_FILE = ROOT / "data" / "case_meta.yaml"

MODULE_ORDER = [
    "tests/api/test_auth.py",
    "tests/api/test_catalog.py",
    "tests/api/test_cart.py",
    "tests/api/test_address.py",
    "tests/api/test_order.py",
    "tests/api/test_favorites_history.py",
    "tests/api/test_after_sales_reviews.py",
    "tests/api/test_admin.py",
    "tests/api/test_security.py",
    "tests/api/test_data_driven.py",
    "tests/api/test_smoke.py",
    "tests/ui/test_mall_browse.py",
    "tests/ui/test_mall_login.py",
    "tests/ui/test_mall_cart_checkout.py",
    "tests/ui/test_mall_orders.py",
    "tests/ui/test_admin_console.py",
    "tests/e2e/test_purchase_flow.py",
]

records = []


class Collector:
    def pytest_collection_finish(self, session):
        for item in session.items:
            markers = {m.name for m in item.iter_markers()}
            if "e2e" in markers:
                case_type = "E2E"
            elif "ui" in markers:
                case_type = "UI"
            else:
                case_type = "API"

            callspec = getattr(item, "callspec", None)
            params = dict(callspec.params) if callspec else {}
            params.pop("browser_name", None)
            obj = getattr(item, "obj", None)
            doc = (getattr(obj, "__doc__", "") or "").strip().splitlines()
            records.append(
                {
                    "nodeid": item.nodeid,
                    "file": item.location[0],
                    "function": item.location[2],
                    "function_name": item.name.split("[")[0],
                    "type": case_type,
                    "markers": sorted(markers),
                    "params": params,
                    "doc": doc[0].strip() if doc else "",
                }
            )


def load_meta() -> dict:
    with open(META_FILE, "r", encoding="utf-8") as f:
        return yaml.safe_load(f)


def param_text(params: dict) -> str:
    parts = []
    for key, value in params.items():
        if key == "case" and isinstance(value, dict):
            parts.append(f"case={value.get('id', value)}")
        elif isinstance(value, dict):
            parts.append(f"{key}={value}")
        else:
            parts.append(f"{key}={value}")
    return ", ".join(parts)


def priority_of(markers: list) -> str:
    for level in ("P0", "P1", "P2"):
        if level in markers:
            return level
    return "P2"


def flags_of(markers: list) -> str:
    flags = [m for m in markers if m in ("smoke", "security", "regression")]
    return "/".join(flags) if flags else "-"


REGISTRY = ROOT / "data" / "case_registry.json"


def content_hash(case: dict) -> str:
    import hashlib

    payload = "\x1f".join(
        str(case.get(key) or "")
        for key in ("title", "verify", "params", "priority", "file")
    )
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()[:16]


def load_registry() -> dict:
    if REGISTRY.exists():
        try:
            data = json.loads(REGISTRY.read_text(encoding="utf-8"))
            return data.get("cases") or {}
        except Exception:  # noqa: BLE001
            pass
    return {}


def build_cases() -> list:
    meta = load_meta()
    modules = meta["modules"]
    titles = meta["cases"]
    registry = load_registry()
    today = __import__("datetime").date.today().isoformat()
    next_seq: dict[str, int] = {}
    for entry in registry.values():
        case_id = entry.get("case_id") or ""
        group, _, seq = case_id.rpartition("-")
        if group and seq.isdigit():
            next_seq[group] = max(next_seq.get(group, 0), int(seq))

    cases = []
    seen: set[str] = set()
    for raw in records:
        module_conf = modules.get(raw["file"], {"name": raw["file"], "prefix": "MISC"})
        prefix = module_conf["prefix"]
        if raw["type"] == "E2E":
            group = "E2E"
        else:
            group = f"{raw['type']}-{prefix}"
        nodeid = raw["nodeid"]
        entry = registry.get(nodeid)
        if entry and entry.get("case_id"):
            case_id = entry["case_id"]  # 旧号优先，永不重排
            first_seen = entry.get("first_seen") or today
        else:
            next_seq[group] = next_seq.get(group, 0) + 1
            case_id = f"{group}-{next_seq[group]:03d}"
            first_seen = today

        key = f"{raw['file']}::{raw['function_name']}"
        meta_case = titles.get(key)
        if meta_case is None:
            print(f"[warn] 缺少用例元数据：{key}", file=sys.stderr)
            meta_case = {"title": raw["function"], "verify": raw["doc"]}

        title = meta_case["title"]
        param_titles = meta_case.get("param_titles") or {}
        for param_key in ("path", "keyword", "sort", "case", "quantity"):
            value = raw["params"].get(param_key)
            if value is not None and str(value) in param_titles:
                title = param_titles[str(value)]
                break

        case = {
            "case_id": case_id,
            "type": raw["type"],
            "module": module_conf["name"],
            "file": raw["file"],
            "title": title,
            "verify": meta_case["verify"],
            "priority": priority_of(raw["markers"]),
            "flags": flags_of(raw["markers"]),
            "params": param_text(raw["params"]),
            "script": nodeid,
            "markers": raw["markers"],
        }
        case["content_hash"] = content_hash(case)
        case["title_hash"] = __import__("hashlib").sha256(
            (title or "").encode("utf-8")
        ).hexdigest()[:16]
        case["first_seen"] = first_seen
        case["last_seen"] = today
        case["status"] = "active"
        cases.append(case)
        seen.add(nodeid)

    # 保留 retired 用例（本次未收集到），不删除历史编号
    for nodeid, entry in registry.items():
        if nodeid in seen:
            continue
        entry = dict(entry)
        entry["status"] = "retired"
        registry[nodeid] = entry
    for case in cases:
        registry[case["script"]] = {
            key: case[key]
            for key in ("case_id", "title", "file", "priority", "content_hash",
                        "title_hash", "first_seen", "last_seen", "status")
        }
    REGISTRY.parent.mkdir(parents=True, exist_ok=True)
    REGISTRY.write_text(
        json.dumps(
            {"generated_at": __import__("datetime").datetime.now().isoformat(timespec="seconds"),
             "total": len(registry), "cases": registry},
            ensure_ascii=False, indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    return cases


def sort_key(case: dict):
    if case["file"] in MODULE_ORDER:
        return (MODULE_ORDER.index(case["file"]), case["case_id"])
    return (len(MODULE_ORDER), case["case_id"])


def write_json(cases: list, generated_at: str) -> None:
    payload = {
        "generated_at": generated_at,
        "source": "pytest --collect-only",
        "total": len(cases),
        "cases": cases,
    }
    (ROOT / "test_cases.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )


def write_csv(cases: list) -> None:
    columns = [
        "用例ID",
        "类型",
        "模块",
        "用例标题",
        "验证点",
        "优先级",
        "标记",
        "参数",
        "自动化脚本",
    ]
    with open(ROOT / "test_cases.csv", "w", encoding="utf-8-sig", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(columns)
        for c in cases:
            writer.writerow(
                [
                    c["case_id"],
                    c["type"],
                    c["module"],
                    c["title"],
                    c["verify"],
                    c["priority"],
                    c["flags"],
                    c["params"],
                    c["script"],
                ]
            )


def write_markdown(cases: list, generated_at: str) -> None:
    by_type = OrderedDict()
    for c in cases:
        by_type.setdefault(c["type"], []).append(c)

    lines = [
        "# 尚品汇自动化测试用例库",
        "",
        "> 本文件由 `scripts/export_cases.py` 从 pytest 实际收集结果生成，请勿手工修改。",
        "> 用例标题与验证点维护在 `data/case_meta.yaml`；新增/删除用例后重跑导出脚本。",
        "",
        f"- 生成时间：{generated_at}",
        f"- 用例总数：**{len(cases)}**",
        f"- 数据源：`pytest --collect-only`（与实际执行条目一致）",
        "",
        "## 概览",
        "",
        "| 类型 | 条数 |",
        "|---|---:|",
    ]
    for case_type, items in by_type.items():
        lines.append(f"| {case_type} | {len(items)} |")
    lines.append(f"| **合计** | **{len(cases)}** |")
    lines.append("")

    for case_type, items in by_type.items():
        lines += [f"## {case_type} 用例（{len(items)} 条）", ""]
        modules = OrderedDict()
        for c in items:
            modules.setdefault(c["module"], []).append(c)
        for module_name, module_items in modules.items():
            lines += [f"### {module_name}（{len(module_items)} 条）", ""]
            lines.append("| 用例ID | 优先级 | 用例标题 | 验证点 | 参数 | 自动化脚本 |")
            lines.append("|---|---|---|---|---|---|")
            for c in module_items:
                lines.append(
                    f"| {c['case_id']} | {c['priority']} | {c['title']} | "
                    f"{c['verify']} | {c['params'] or '-'} | `{c['script']}` |"
                )
            lines.append("")

    (ROOT / "test_cases.md").write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    print(f"[collect] {ROOT}")
    exit_code = pytest.main(
        ["--collect-only", "-q", "-p", "no:cacheprovider"],
        plugins=[Collector()],
    )
    if int(exit_code) != 0:
        print(f"[error] pytest 收集失败：exit={exit_code}", file=sys.stderr)
        return int(exit_code)

    cases = sorted(build_cases(), key=sort_key)
    generated_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    write_json(cases, generated_at)
    write_csv(cases)
    write_markdown(cases, generated_at)

    print(f"[done] {len(cases)} 条用例")
    print(f"  - {ROOT / 'test_cases.md'}")
    print(f"  - {ROOT / 'test_cases.csv'}")
    print(f"  - {ROOT / 'test_cases.json'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
