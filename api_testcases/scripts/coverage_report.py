#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""覆盖矩阵报告（S1）：交叉核对"用例清单 × 自动化接入"，按优先级/模块/类型输出。

用法：python3 scripts/coverage_report.py [--jsonout artifacts/coverage.json]
"""
from __future__ import annotations
import argparse
import json
import re
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "scripts"))
import case_lint as L  # noqa: E402


def marker_ids() -> set[str]:
    out = subprocess.run(
        ["rg", "-o", r'zt_case\(\s*["\']([^"\']+)["\']', "tests", "-N", "--no-filename", "-r", "$1"],
        capture_output=True, text=True, cwd=str(ROOT)).stdout
    return {line.strip() for line in out.splitlines() if line.strip()}


def driven_ids() -> set[str]:
    from core.inventory_driven import build_params
    return {e["case_id"] for e in build_params() if isinstance(e.get("case_id"), str)}


def family(case_id: str) -> str:
    m = re.match(r"(?:POST|GET|PUT|DELETE|PATCH)_V1_([A-Z]+)", case_id)
    if m:
        return m.group(1).lower()
    m = re.match(r"BUS-([A-Z]+)-", case_id)
    return ("bus-" + m.group(1).lower()) if m else "other"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--jsonout", type=Path, default=ROOT / "artifacts" / "coverage.json")
    args = ap.parse_args()
    rows = L.parse_inventory(L.DEFAULT_INV)
    driven, markers = driven_ids(), marker_ids()
    covered = {i for i in (driven | markers) if isinstance(i, str)}
    total, cov = len(rows), sum(1 for r in rows if r["case_id"] in covered)
    print(f"用例总数 {total} | 执行器参数化 {len(driven)} | 手工标记 {len(markers)} | 并集覆盖 {cov} = {cov/total*100:.0f}%")
    for pri in ("P0", "P1", "P2", "P3"):
        tot = [r for r in rows if r["priority"] == pri]
        got = [r for r in tot if r["case_id"] in covered]
        if tot:
            print(f"  {pri}: {len(got)}/{len(tot)} = {len(got)/len(tot)*100:.0f}%")
    per_family: dict[str, Counter] = defaultdict(Counter)
    for r in rows:
        f = family(r["case_id"])
        per_family[f]["total"] += 1
        if r["case_id"] in covered:
            per_family[f]["covered"] += 1
    print("按模块（覆盖/总数）:", " ".join(f"{f}:{c['covered']}/{c['total']}"
          for f, c in sorted(per_family.items())))
    uncovered_p0 = [r["case_id"] for r in rows if r["priority"] == "P0" and r["case_id"] not in covered]
    if uncovered_p0:
        print(f"未覆盖 P0 ×{len(uncovered_p0)}:", "、".join(uncovered_p0[:12]) + ("…" if len(uncovered_p0) > 12 else ""))
    args.jsonout.parent.mkdir(parents=True, exist_ok=True)
    args.jsonout.write_text(json.dumps({
        "total": total, "covered": sorted(covered), "driven": sorted(driven), "markers": sorted(markers),
        "by_priority": {p: {"total": sum(1 for r in rows if r["priority"] == p),
                            "covered": sum(1 for r in rows if r["priority"] == p and r["case_id"] in covered)}
                        for p in ("P0", "P1", "P2", "P3")},
    }, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"已写入 {args.jsonout}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
