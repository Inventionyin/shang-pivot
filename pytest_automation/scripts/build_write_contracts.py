#!/usr/bin/env python3
"""把 data/write_contracts.yaml 转成机读契约，并扫描已有测试的覆盖情况。

输出：artifacts/write-contracts.json
  {generated_at, endpoints: {"METHOD /path": {table, locate, fields, special, generated, tests:[file]}}}
"""
from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "data" / "write_contracts.yaml"
OUT = ROOT / "artifacts" / "write-contracts.json"


def main() -> None:
    data = yaml.safe_load(SRC.read_text(encoding="utf-8")) or {}
    endpoints = data.get("endpoints") or {}
    test_files = [
        p for p in (ROOT / "tests").rglob("*.py") if p.name.startswith("test_")
    ]
    for endpoint, contract in endpoints.items():
        tests = []
        for file in test_files:
            text = file.read_text(encoding="utf-8", errors="replace")
            if endpoint in text and "verify_write" in text:
                tests.append(str(file.relative_to(ROOT)))
        contract["tests"] = sorted(set(tests))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(
        json.dumps(
            {"generated_at": datetime.now().isoformat(timespec="seconds"), "endpoints": endpoints},
            ensure_ascii=False,
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    covered = sum(1 for c in endpoints.values() if c.get("tests"))
    print(f"[done] 写接口契约 {len(endpoints)} 个，已被测试覆盖 {covered} 个 -> {OUT}")


if __name__ == "__main__":
    main()
