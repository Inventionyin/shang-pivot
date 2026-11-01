#!/usr/bin/env python3
"""合并并行两阶段产物，输出与串行完全同 schema 的三份文件。

输入（运行目录内）：
  case_endpoint_map.json / case_endpoint_map.gw*.json
  results.json / results.gw*.json
输出：
  case_endpoint_map.json  —— tests 并集、details/samples 合并（schema 不变）
  results.json            —— results 合并，exit_status 取最严格
接口视图生成器（build_api_matrix.py）无需任何改动即可消费。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path


def merge_mapping(run_dir: Path) -> dict:
    tests: dict[str, list[str]] = {}
    details: dict[str, dict] = {}
    samples: dict[str, dict] = {}
    files = sorted(run_dir.glob("case_endpoint_map*.json"))
    for file in files:
        data = json.loads(file.read_text(encoding="utf-8"))
        for nodeid, endpoints in (data.get("tests") or {}).items():
            merged = set(tests.get(nodeid, [])) | set(endpoints)
            tests[nodeid] = sorted(merged)
        details.update(data.get("details") or {})
        samples.update(data.get("samples") or {})
    return {"tests": tests, "details": details, "samples": samples}


def merge_manifests(run_dir: Path) -> dict | None:
    files = sorted(run_dir.glob("run-manifest*.json"))
    if not files:
        return None
    base = None
    workers = []
    totals = {"passed": 0, "failed": 0, "skipped": 0}
    total = 0
    for file in files:
        data = json.loads(file.read_text(encoding="utf-8"))
        workers.append(data.get("worker"))
        total += int(data.get("total") or 0)
        for key in totals:
            totals[key] += int((data.get("counts") or {}).get(key) or 0)
        if base is None or data.get("worker") == "master":
            base = data
    merged = dict(base or {})
    merged["worker"] = "merged"
    merged["workers"] = workers
    merged["counts"] = totals
    merged["total"] = total
    (run_dir / "run-manifest.json").write_text(
        json.dumps(merged, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return merged


def merge_results(run_dir: Path) -> dict:
    results: list[dict] = []
    exit_status = 0
    env = ""
    for file in sorted(run_dir.glob("results*.json")):
        data = json.loads(file.read_text(encoding="utf-8"))
        results.extend(data.get("results") or [])
        exit_status = max(exit_status, int(data.get("exit_status") or 0))
        env = data.get("env") or env
    return {"exit_status": exit_status, "env": env, "worker": "merged", "results": results}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", required=True)
    args = parser.parse_args()
    run_dir = Path(args.run_dir)

    mapping = merge_mapping(run_dir)
    (run_dir / "case_endpoint_map.json").write_text(
        json.dumps(mapping, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    merged = merge_results(run_dir)
    (run_dir / "results.json").write_text(
        json.dumps(merged, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    manifest = merge_manifests(run_dir)
    workers = len(list(run_dir.glob("results.*.json")))
    print(
        f"[done] 合并 {workers} 个 worker 产物："
        f"{len(mapping['tests'])} 条用例映射，{len(merged['results'])} 条结果"
        + (f"，run_id={manifest.get('run_id')}" if manifest else "")
    )


if __name__ == "__main__":
    main()
