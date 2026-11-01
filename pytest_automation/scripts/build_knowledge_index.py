#!/usr/bin/env python3
"""构建版本化知识库（SQLite FTS5）：用例/端点/调用链/字段映射/基线/历史/缺陷。

输出：artifacts/knowledge/kb.sqlite
每条记录带 commit 与 baseline，便于按版本检索。
"""
from __future__ import annotations

import json
import sqlite3
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPO = ROOT.parent
OUT = ROOT / "artifacts" / "knowledge"


def load(path: Path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001
        return default


def commit() -> str:
    try:
        return subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=REPO,
                              capture_output=True, text=True, timeout=5).stdout.strip() or "-"
    except Exception:  # noqa: BLE001
        return "-"


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    db_path = OUT / "kb.sqlite"
    if db_path.exists():
        db_path.unlink()
    conn = sqlite3.connect(db_path)
    conn.executescript(
        """
        CREATE TABLE docs (
            id TEXT PRIMARY KEY, kind TEXT, title TEXT, body TEXT,
            git_commit TEXT, baseline TEXT, source TEXT
        );
        CREATE VIRTUAL TABLE docs_fts USING fts5(
            title, body, content='docs', content_rowid='rowid'
        );
        """
    )
    git_sha = commit()
    baseline = sorted((REPO / "api_testcases" / "baselines").glob("*"))
    baseline_label = baseline[-1].name if baseline else "-"
    rows = []

    meta = load(ROOT / "data" / "case_meta.yaml", None)
    try:
        import yaml

        meta = yaml.safe_load((ROOT / "data" / "case_meta.yaml").read_text(encoding="utf-8")) or {}
    except Exception as exc:  # noqa: BLE001
        print(f"[warn] case_meta.yaml 未加载（{exc}）；用 venv 运行可索引用例元数据", file=sys.stderr)
        meta = {}
    for key, value in (meta.get("cases") or {}).items():
        rows.append((f"case:{key}", "case", value.get("title", ""),
                     value.get("verify", ""), git_sha, baseline_label, key))

    registry = load(ROOT / "data" / "case_registry.json", {})
    for nodeid, item in (registry.get("cases") or {}).items():
        rows.append((f"registry:{item.get('case_id')}", "case", item.get("title", ""),
                     f"{nodeid}｜{item.get('file', '')}｜{item.get('priority', '')}｜{item.get('status', '')}",
                     git_sha, baseline_label, "case_registry.json"))

    endpoints = load(REPO / "api_testcases" / "artifacts" / "endpoint-model.json", {}).get("endpoints") or []
    for item in endpoints:
        eid = f"{item['method']} {item['path']}"
        rows.append((f"endpoint:{eid}", "endpoint", eid,
                     item.get("summary", ""), git_sha, baseline_label, "endpoint-model.json"))

    graph = load(REPO / "api_testcases" / "baselines" / baseline_label / "call-graph.json", {})
    for item in graph.get("endpoints") or []:
        eid = item.get("endpoint", "")
        body = " -> ".join(filter(None, [item.get("controller")] + (item.get("services") or []) +
                                   (item.get("tables") or [])))
        rows.append((f"chain:{eid}", "chain", eid, body, git_sha, baseline_label, "call-graph.json"))

    field_map = load(REPO / "api_testcases" / "baselines" / baseline_label / "field-map.json", {})
    for name, entity in (field_map.get("entities") or {}).items():
        rows.append((f"table:{entity.get('table')}", "table", entity.get("table", name),
                     " ".join(f.get("field", "") + ":" + f.get("column", "") if f.get("column") else f.get("field", "")
                              for f in entity.get("fields") or []),
                     git_sha, baseline_label, "field-map.json"))

    history = load(ROOT / "artifacts" / "allure-report" / "ext" / "data" / "history.json", {})
    if history.get("current"):
        rows.append((f"history:{history['current']}", "history", f"运行 {history['current']}",
                     json.dumps(history.get("summary"), ensure_ascii=False), git_sha, baseline_label, "history.json"))

    defects = load(ROOT / "artifacts" / "allure-report" / "ext" / "data" / "defects.json", {})
    for item in defects.get("defects") or []:
        rows.append((f"defect:{item.get('id')}", "defect", item.get("caseId", ""),
                     f"{item.get('summary')} {item.get('endpoint')} {(item.get('code') or {}).get('location', '')}",
                     git_sha, baseline_label, "defects.json"))

    conn.executemany(
        "INSERT OR REPLACE INTO docs(id, kind, title, body, git_commit, baseline, source) VALUES (?,?,?,?,?,?,?)",
        rows,
    )
    conn.execute("INSERT INTO docs_fts(docs_fts) VALUES('rebuild')")
    conn.commit()
    conn.close()
    print(f"[done] 知识库 {len(rows)} 条 -> {db_path}")


if __name__ == "__main__":
    main()
