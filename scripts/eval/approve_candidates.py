"""Human approval gate for mined candidates.

Usage:
  python scripts/eval/approve_candidates.py \
    --candidates /tmp/reference-candidates.jsonl \
    --approve C-123,C-456 --out /tmp/reference-approved.jsonl

Only approved rows leave the candidate state; nothing is auto-published.
"""
from __future__ import annotations

import argparse
import sys
from reference_common import read_jsonl, write_jsonl


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="人工审批 trace 候选")
    parser.add_argument("--candidates", required=True)
    parser.add_argument("--approve", required=True, help="逗号分隔 sourceId")
    parser.add_argument("--out", required=True)
    args = parser.parse_args(argv)
    approved_ids = {item.strip() for item in args.approve.split(",") if item.strip()}
    if not approved_ids:
        print("审批列表为空", file=sys.stderr)
        return 2
    rows = read_jsonl(args.candidates)
    selected = [row for row in rows if str(row.get("sourceId")) in approved_ids]
    missing = sorted(approved_ids - {str(row.get("sourceId")) for row in selected})
    for row in selected:
        row["datasetVersion"] = "candidate-approved"
        row["note"] = "人工已审批；仍需补 turns/expected 后入正式 suite"
    write_jsonl(args.out, selected)
    print(f"[approve] candidates={len(rows)} approved={len(selected)} out={args.out}")
    if missing:
        print(f"[approve] not found: {missing}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
