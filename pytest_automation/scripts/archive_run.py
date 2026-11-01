#!/usr/bin/env python3
"""归档一轮运行：把 run-manifest/results/mapping/coverage/registry 复制到 artifacts/history/<run_id>/。"""
from __future__ import annotations

import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main() -> None:
    artifacts = ROOT / "artifacts"
    manifest_file = artifacts / "run-manifest.json"
    if not manifest_file.exists():
        print("[skip] 未找到 run-manifest.json，跳过归档")
        return
    manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
    run_id = manifest.get("run_id") or "unknown"
    target = artifacts / "history" / run_id
    target.mkdir(parents=True, exist_ok=True)
    for name in ("run-manifest.json", "results.json", "case_endpoint_map.json",
                 "db-baseline-diff.json"):
        source = artifacts / name
        if source.exists():
            shutil.copyfile(source, target / name)
    coverage = artifacts / "allure-report" / "ext" / "data" / "coverage.json"
    if coverage.exists():
        shutil.copyfile(coverage, target / "coverage.json")
    registry = ROOT / "data" / "case_registry.json"
    if registry.exists():
        shutil.copyfile(registry, target / "case_registry.json")
    print(f"[done] 已归档运行 {run_id} -> {target}")


if __name__ == "__main__":
    main()
