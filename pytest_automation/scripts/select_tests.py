#!/usr/bin/env python3
"""按代码/需求变更选测：git diff → 受影响端点 → 关联用例。

数据源：
  ../api_testcases/baselines/<commit>/call-graph.json（端点 → Controller/Service/表）
  artifacts/case_endpoint_map.json（用例 → 实际调用端点）

输出：
  artifacts/selection/selection.json   变更文件、受影响端点、选中用例、需求变更
  artifacts/selection/nodeids.txt      每行一个 nodeid，可直接 pytest @file
  artifacts/selection/requirements.md  需求文档变更摘录（供夜间报告引用）

用法：
  python3 scripts/select_tests.py --since origin/main
  python3 scripts/select_tests.py --since HEAD~3 --fallback-smoke
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPO = ROOT.parent
CALL_GRAPH_DIR = REPO / "api_testcases" / "baselines"
REQUIREMENT_DOC = "requirementTracking.markdown"


def run(cmd: list[str], cwd: Path = REPO) -> str:
    return subprocess.run(
        cmd, cwd=cwd, capture_output=True, text=True, timeout=30
    ).stdout


def git_meta(since: str) -> dict:
    commit = run(["git", "rev-parse", "--short", "HEAD"]).strip() or "-"
    branch = run(["git", "rev-parse", "--abbrev-ref", "HEAD"]).strip() or "-"
    changed = [
        line.strip()
        for line in run(["git", "diff", "--name-only", f"{since}...HEAD"]).splitlines()
        if line.strip()
    ]
    if not changed:
        changed = [
            line.strip()
            for line in run(["git", "diff", "--name-only", "HEAD~1"]).splitlines()
            if line.strip()
        ]
    return {"commit": commit, "branch": branch, "since": since, "changedFiles": changed}


def load_call_graph() -> dict:
    candidates = sorted(CALL_GRAPH_DIR.glob("*/call-graph.json"))
    if not candidates:
        return {}
    return json.loads(candidates[-1].read_text(encoding="utf-8"))


def load_case_map() -> dict:
    path = ROOT / "artifacts" / "case_endpoint_map.json"
    if not path.exists():
        return {"tests": {}}
    return json.loads(path.read_text(encoding="utf-8"))


def endpoint_index(call_graph: dict) -> dict[str, set[str]]:
    """类名 → {端点}，覆盖 Controller 与 Service。"""
    index: dict[str, set[str]] = {}
    for ep in call_graph.get("endpoints") or []:
        name = ep.get("endpoint") or f"{ep.get('http')} {ep.get('path')}"
        for cls in [ep.get("controller"), *(ep.get("services") or [])]:
            if cls:
                index.setdefault(cls, set()).add(name)
    return index


def table_index(call_graph: dict) -> dict[str, set[str]]:
    index: dict[str, set[str]] = {}
    for ep in call_graph.get("endpoints") or []:
        name = ep.get("endpoint") or f"{ep.get('http')} {ep.get('path')}"
        for table in ep.get("tables") or []:
            index.setdefault(table.split(".")[-1], set()).add(name)
    return index


def java_class(path: str) -> str | None:
    match = re.search(r"/src/main/java/.*/(?:[\w]+/)*([\w$]+)\.java$", path)
    return match.group(1).split("$")[0] if match else None


def migration_tables(diff_text: str) -> set[str]:
    return {
        match.group(1).lower()
        for match in re.finditer(
            r"(?:CREATE\s+TABLE|ALTER\s+TABLE|DROP\s+TABLE)\s+(?:IF\s+EXISTS\s+)?[\w.]*?(\w+)",
            diff_text,
            flags=re.IGNORECASE,
        )
    }


def requirement_changes(since: str) -> str:
    diff = run(["git", "diff", f"{since}...HEAD", "--", REQUIREMENT_DOC]) or run(
        ["git", "diff", "HEAD~1", "--", REQUIREMENT_DOC]
    )
    if not diff:
        return ""
    lines = []
    for line in diff.splitlines():
        if line.startswith(("+++", "---", "@@")):
            continue
        if line.startswith("+") and (line[1:].strip().startswith("#") or "|" in line):
            lines.append(line[1:].rstrip())
    return "\n".join(lines[:200])


SOURCE_ROOTS = ("backend/", "shang-mall/src/", "shang-admin-web/src/")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--since", default="origin/main")
    parser.add_argument("--fallback-smoke", action="store_true",
                        help="无受影响用例时退回 smoke 标记")
    args = parser.parse_args()

    meta = git_meta(args.since)
    call_graph = load_call_graph()
    endpoints_by_class = endpoint_index(call_graph)
    endpoints_by_table = table_index(call_graph)

    affected: set[str] = set()
    changed_migrations: list[str] = []
    source_files = [
        path for path in meta["changedFiles"] if path.startswith(SOURCE_ROOTS)
    ]
    for path in source_files:
        cls = java_class(path)
        if cls:
            affected |= endpoints_by_class.get(cls, set())
            continue
        if path.endswith(".sql"):
            changed_migrations.append(path)
            diff = run(["git", "diff", f"{args.since}...HEAD", "--", path])
            for table in migration_tables(diff):
                affected |= endpoints_by_table.get(table, set())

    case_map = load_case_map()
    nodeids: list[str] = []
    for nodeid, eps in (case_map.get("tests") or {}).items():
        if affected & set(eps):
            nodeids.append(nodeid)
    nodeids.sort()

    fallback = ""
    if not nodeids and args.fallback_smoke:
        fallback = "smoke（未匹配到受影响用例，退回冒烟集）"

    out_dir = ROOT / "artifacts" / "selection"
    out_dir.mkdir(parents=True, exist_ok=True)
    requirements = requirement_changes(args.since)
    payload = {
        "generatedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "git": {"commit": meta["commit"], "branch": meta["branch"], "since": meta["since"]},
        "changedFiles": meta["changedFiles"],
        "sourceFiles": source_files,
        "changedMigrations": changed_migrations,
        "affectedEndpoints": sorted(affected),
        "selectedTests": nodeids,
        "testCount": len(nodeids),
        "fallback": fallback,
        "requirementChanges": bool(requirements),
    }
    (out_dir / "selection.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    (out_dir / "nodeids.txt").write_text("\n".join(nodeids) + ("\n" if nodeids else ""),
                                          encoding="utf-8")
    (out_dir / "requirements.md").write_text(requirements, encoding="utf-8")
    print(
        f"[done] 变更 {len(meta['changedFiles'])} 个文件 → 端点 {len(affected)} 个"
        f" → 用例 {len(nodeids)} 条{('（' + fallback + '）') if fallback else ''}"
    )


if __name__ == "__main__":
    main()
