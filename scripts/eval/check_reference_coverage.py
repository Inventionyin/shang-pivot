"""Coverage gate: every ON SPU must have a generated named-add case.

Usage:
  python scripts/eval/check_reference_coverage.py \
    --catalog /tmp/catalog.json \
    --dataset /tmp/reference-cart-auto.jsonl \
    --report /tmp/reference-coverage.md

Exit 1 when a SPU is missing, duplicated, or a case lacks a fail-able assertion.
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path
from typing import Any

from reference_common import AUTO_SUITE, catalog_products, load_json, read_jsonl


def validate_case(row: dict[str, Any]) -> list[str]:
    problems: list[str] = []
    expected = row.get("expected") or {}
    sources = expected.get("sourceFragments") or []
    keywords = expected.get("answerKeywords") or []
    if not sources and not keywords:
        problems.append("缺少 source/answer 断言")
    if not expected.get("cardFragments"):
        problems.append("缺少 cardFragments")
    if not row.get("turns"):
        problems.append("缺少 turns")
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="全目录指代加购覆盖率门禁")
    parser.add_argument("--catalog", required=True)
    parser.add_argument("--dataset", required=True)
    parser.add_argument("--report", default="")
    args = parser.parse_args(argv)

    products = catalog_products(load_json(args.catalog))
    rows = [row for row in read_jsonl(args.dataset)
            if (row.get("payload") or {}).get("suite") == AUTO_SUITE]
    by_spu: dict[int, dict[str, Any]] = {}
    duplicates: list[int] = []
    case_problems: list[str] = []
    for row in rows:
        spu = (row.get("payload") or {}).get("spuId")
        try:
            spu_id = int(spu)
        except (TypeError, ValueError):
            continue
        if spu_id in by_spu:
            duplicates.append(spu_id)
        by_spu[spu_id] = row
        for problem in validate_case(row):
            case_problems.append(f"{row.get('sourceId')}: {problem}")

    required = {int(product["spuId"]): product for product in products}
    missing = sorted(set(required) - set(by_spu))
    extra = sorted(set(by_spu) - set(required))
    category_counts = Counter(str(product.get("categoryName") or "未分类") for product in products)
    covered_categories = Counter(
        str(required[spu].get("categoryName") or "未分类") for spu in by_spu if spu in required)

    lines = [
        "# 全品类指代加购覆盖率报告",
        "",
        f"- 在售 SPU：{len(required)}",
        f"- 已覆盖：{len(required) - len(missing)}",
        f"- 缺失：{len(missing)}",
        f"- 重复 sourceId：{len(duplicates)}",
        f"- 用例断言问题：{len(case_problems)}",
        "",
        "| 类目 | 在售 | 已覆盖 |",
        "|---|---:|---:|",
    ]
    for category, total in sorted(category_counts.items()):
        lines.append(f"| {category} | {total} | {covered_categories.get(category, 0)} |")
    if missing:
        lines.extend(["", "## 缺失 SPU", ""])
        lines.extend(f"- {spu}: {required[spu].get('name')}" for spu in missing)
    if duplicates:
        lines.extend(["", "## 重复 SPU", ""])
        lines.extend(f"- {spu}" for spu in duplicates)
    if case_problems:
        lines.extend(["", "## 用例问题", ""])
        lines.extend(f"- {problem}" for problem in case_problems)
    report = "\n".join(lines) + "\n"

    if args.report:
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(report, encoding="utf-8")
    print(report)
    print(json.dumps({
        "total": len(required),
        "covered": len(required) - len(missing),
        "missing": missing,
        "duplicates": duplicates,
        "caseProblems": case_problems,
    }, ensure_ascii=False))

    return 0 if not missing and not duplicates and not case_problems else 1


if __name__ == "__main__":
    raise SystemExit(main())
