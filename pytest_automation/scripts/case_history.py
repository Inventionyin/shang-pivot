#!/usr/bin/env python3
"""跨轮 diff：用例新增/删除/修改 + 结果迁移，输出报告「用例历史」数据。

对比对象：artifacts/history/<run_id>/ 下最近两轮归档。
输出：artifacts/history/<run_id>/diff.json、artifacts/allure-report/ext/data/history.json
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HISTORY = ROOT / "artifacts" / "history"
REPORT_DATA = ROOT / "artifacts" / "allure-report" / "ext" / "data" / "history.json"


def load(path: Path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001
        return default


def outcomes(results_path: Path) -> dict[str, str]:
    data = load(results_path, {})
    latest = {}
    for item in data.get("results") or []:
        latest[item.get("nodeid", "")] = item.get("outcome", "")
    return latest


def registry_cases(path: Path) -> dict[str, dict]:
    return (load(path, {}) or {}).get("cases") or {}


def main() -> None:
    runs = sorted([p for p in HISTORY.glob("*") if (p / "run-manifest.json").exists()])
    if len(runs) < 2:
        REPORT_DATA.parent.mkdir(parents=True, exist_ok=True)
        REPORT_DATA.write_text(json.dumps({"runs": [p.name for p in runs], "latest": None},
                                          ensure_ascii=False, indent=1), encoding="utf-8")
        print("[skip] 历史归档不足两轮，无法 diff")
        return
    prev, curr = runs[-2], runs[-1]
    prev_manifest = load(prev / "run-manifest.json", {})
    curr_manifest = load(curr / "run-manifest.json", {})

    prev_cases = registry_cases(prev / "case_registry.json")
    curr_cases = registry_cases(curr / "case_registry.json")
    added = sorted(set(curr_cases) - set(prev_cases))
    removed = sorted(set(prev_cases) - set(curr_cases))
    modified = sorted(
        key for key in set(prev_cases) & set(curr_cases)
        if prev_cases[key].get("content_hash") != curr_cases[key].get("content_hash")
    )

    prev_out = outcomes(prev / "results.json")
    curr_out = outcomes(curr / "results.json")
    transitions = []
    for nodeid in sorted(set(prev_out) & set(curr_out)):
        if prev_out[nodeid] != curr_out[nodeid]:
            transitions.append({
                "nodeid": nodeid,
                "case_id": (curr_cases.get(nodeid) or {}).get("case_id", ""),
                "title": (curr_cases.get(nodeid) or {}).get("title", ""),
                "from": prev_out[nodeid],
                "to": curr_out[nodeid],
            })
    new_failures = [t for t in transitions if t["to"] == "failed"]
    fixed = [t for t in transitions if t["from"] == "failed" and t["to"] == "passed"]

    prev_cov = load(prev / "coverage.json", {}).get("summary") or {}
    curr_cov = load(curr / "coverage.json", {}).get("summary") or {}
    coverage_delta = {
        key: {"before": prev_cov.get(key), "after": curr_cov.get(key)}
        for key in set(prev_cov) | set(curr_cov)
        if prev_cov.get(key) != curr_cov.get(key)
    }

    payload = {
        "current": curr_manifest.get("run_id", curr.name),
        "previous": prev_manifest.get("run_id", prev.name),
        "git": {"current": (curr_manifest.get("git") or {}).get("commit"),
                "previous": (prev_manifest.get("git") or {}).get("commit")},
        "summary": {
            "casesAdded": len(added), "casesRemoved": len(removed),
            "casesModified": len(modified), "newFailures": len(new_failures),
            "fixed": len(fixed), "transitions": len(transitions),
        },
        "added": [[key, curr_cases[key].get("case_id"), curr_cases[key].get("title")] for key in added],
        "removed": [[key, prev_cases[key].get("case_id"), prev_cases[key].get("title")] for key in removed],
        "modified": [[key, curr_cases[key].get("case_id"), curr_cases[key].get("title")] for key in modified],
        "transitions": transitions,
        "coverageDelta": coverage_delta,
    }
    (curr / "diff.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    REPORT_DATA.parent.mkdir(parents=True, exist_ok=True)
    REPORT_DATA.write_text(json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8")
    print(
        f"[done] 历史 diff {payload['previous']} -> {payload['current']}："
        f"新增 {len(added)} / 删除 {len(removed)} / 修改 {len(modified)} / 新失败 {len(new_failures)} / 修复 {len(fixed)}"
    )


if __name__ == "__main__":
    main()
