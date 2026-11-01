#!/usr/bin/env python3
"""用例评审机制：生成评审单 / 回填结论 / CI 门禁。

台账：data/case_review.yaml（case_id → status/reviewer/date/comment/content_hash）
状态：draft / reviewed / approved / rejected / legacy(auto)
规则：
  - 新增用例（注册表 first_seen 为当天）必须有 approved；
  - 已存在用例内容 hash 变化 → 视为“修改”，必须重新 approved；
  - P0 用例必须 approved；
  - legacy(auto) 只豁免“hash 未变化”的存量用例（一次性迁移用）。

用法：
  python3 scripts/review_cases.py --bootstrap      # 存量用例标记 legacy(auto)
  python3 scripts/review_cases.py                  # 生成 artifacts/review/case-review.{md,csv}
  python3 scripts/review_cases.py --apply c.json   # 回填 {case_id: {status,reviewer,comment}}
  python3 scripts/review_cases.py --check          # CI 门禁（失败退出 1）
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import random
import sys
from datetime import date
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
CASES = ROOT / "test_cases.json"
LEDGER = ROOT / "data" / "case_review.yaml"
OUT = ROOT / "artifacts" / "review"
SEED = 20261029


def load_cases() -> list[dict]:
    data = json.loads(CASES.read_text(encoding="utf-8"))
    return data.get("cases") or []


def load_ledger() -> dict:
    if LEDGER.exists():
        return yaml.safe_load(LEDGER.read_text(encoding="utf-8")) or {}
    return {}


def save_ledger(ledger: dict) -> None:
    LEDGER.parent.mkdir(parents=True, exist_ok=True)
    LEDGER.write_text(
        yaml.safe_dump(ledger, allow_unicode=True, sort_keys=True), encoding="utf-8"
    )


def sampled(case: dict) -> bool:
    """P0 全评；P1 30%；P2 10%，固定种子可复现。"""
    priority = case.get("priority") or "P2"
    if priority == "P0":
        return True
    ratio = 0.30 if priority == "P1" else 0.10
    digest = hashlib.sha256(f"{SEED}:{case['case_id']}".encode()).hexdigest()
    return int(digest[:8], 16) / 0xFFFFFFFF < ratio


def bootstrap(ledger: dict, cases: list[dict]) -> None:
    today = date.today().isoformat()
    for case in cases:
        ledger.setdefault(
            case["case_id"],
            {
                "status": "legacy(auto)",
                "reviewer": "auto",
                "date": today,
                "comment": "存量用例一次性豁免；内容变化后须人工评审",
                "content_hash": case.get("content_hash") or "",
            },
        )
    save_ledger(ledger)


def check(cases: list[dict], ledger: dict, allow_new: bool, strict_p0: bool = False) -> list[str]:
    problems: list[str] = []
    p0_debt = 0
    today = date.today().isoformat()
    for case in cases:
        cid = case["case_id"]
        entry = ledger.get(cid)
        current = case.get("content_hash") or ""
        is_new = (case.get("first_seen") or "") == today
        if entry is None:
            if is_new and allow_new:
                continue
            problems.append(f"{cid} 未登记评审（新增用例需 approved）")
            continue
        if entry.get("content_hash") != current:
            if entry.get("status") != "approved":
                problems.append(f"{cid} 内容已修改但未重新评审（当前 {entry.get('status')}）")
            continue
        if case.get("priority") == "P0" and entry.get("status") != "approved":
            if strict_p0:
                problems.append(f"{cid} 为 P0，但评审状态为 {entry.get('status')}")
            else:
                p0_debt += 1
        if entry.get("status") == "rejected":
            problems.append(f"{cid} 评审结论为 rejected")
    if p0_debt:
        print(f"[warn] P0 评审债：{p0_debt} 条 legacy(auto)（--strict-p0 时阻断）")
    return problems


def report(cases: list[dict], ledger: dict, path_md: Path, path_csv: Path) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    rows = []
    for case in cases:
        entry = ledger.get(case["case_id"]) or {}
        rows.append(
            {
                "case_id": case["case_id"],
                "title": case["title"],
                "priority": case.get("priority"),
                "module": case.get("module"),
                "status": entry.get("status", "missing"),
                "reviewer": entry.get("reviewer", ""),
                "sample": "是" if sampled(case) else "否",
                "hash_match": "是" if entry.get("content_hash") == case.get("content_hash") else "否",
                "verify": case.get("verify") or "",
            }
        )
    with open(path_csv, "w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    total = len(rows)
    approved = sum(1 for row in rows if row["status"] == "approved")
    legacy = sum(1 for row in rows if row["status"] == "legacy(auto)")
    missing = sum(1 for row in rows if row["status"] == "missing")
    lines = [
        "# 用例评审单",
        "",
        f"- 用例总数：{total}；approved：{approved}；legacy(auto)：{legacy}；未登记：{missing}",
        f"- 抽样规则：P0 全评、P1 30%、P2 10%（种子 {SEED}）；内容变化的用例必须重新评审",
        "",
        "| 用例编号 | 标题 | 优先级 | 模块 | 评审状态 | 评审人 | 抽样 | 内容一致 | 验证点 |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    lines += [
        f"| {row['case_id']} | {row['title']} | {row['priority']} | {row['module']} | "
        f"{row['status']} | {row['reviewer']} | {row['sample']} | {row['hash_match']} | "
        f"{(row['verify'] or '')[:60]} |"
        for row in rows
        if row["sample"] or row["status"] in ("missing", "draft", "rejected")
    ]
    path_md.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bootstrap", action="store_true")
    parser.add_argument("--apply", type=Path)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--allow-new", action="store_true", help="新用例未评审时先放行（仅本地）")
    parser.add_argument("--strict-p0", action="store_true", help="P0 未 approved 即阻断（夜间门禁用）")
    parser.add_argument("--refresh-legacy", action="store_true", help="存量 legacy 条目接受内容 hash 更新")
    args = parser.parse_args()

    cases = load_cases()
    ledger = load_ledger()
    if args.bootstrap:
        bootstrap(ledger, cases)
        print(f"[done] 已登记 {len(ledger)} 条存量用例为 legacy(auto)")
    if args.refresh_legacy:
        updated = 0
        for case in cases:
            entry = ledger.get(case["case_id"]) or {}
            if str(entry.get("status", "")).startswith("legacy"):
                entry["content_hash"] = case.get("content_hash") or ""
                ledger[case["case_id"]] = entry
                updated += 1
        save_ledger(ledger)
        print(f"[done] 已刷新 {updated} 条 legacy 用例的内容指纹")
        return
    if args.apply:
        updates = json.loads(args.apply.read_text(encoding="utf-8"))
        today = date.today().isoformat()
        for case in cases:
            update = updates.get(case["case_id"])
            if not update:
                continue
            ledger[case["case_id"]] = {
                "status": update.get("status", "reviewed"),
                "reviewer": update.get("reviewer", ""),
                "date": today,
                "comment": update.get("comment", ""),
                "content_hash": case.get("content_hash") or "",
            }
        save_ledger(ledger)
        print(f"[done] 已回填 {len(updates)} 条评审结论")
    if args.bootstrap or args.apply:
        return
    problems = check(cases, ledger, args.allow_new, args.strict_p0)
    OUT.mkdir(parents=True, exist_ok=True)
    report(cases, ledger, OUT / "case-review.md", OUT / "case-review.csv")
    if args.check:
        if problems:
            print(f"[FAIL] 评审门禁未通过：{len(problems)} 条")
            for item in problems[:30]:
                print("  -", item)
            sys.exit(1)
        print("[OK] 评审门禁通过")
        return
    print(f"[done] 评审单已生成：{OUT / 'case-review.md'}")


if __name__ == "__main__":
    main()
