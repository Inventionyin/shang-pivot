#!/usr/bin/env python3
"""Run pytest, publish reports, and synchronize the run to ZenTao.

Secrets are inherited only from the current process environment.  The script
always attempts result/Bug synchronization after pytest, then exits with the
original pytest status so CI still detects a failing test.
"""
from __future__ import annotations

import argparse
import datetime
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def run(command: list[str], *, env: dict[str, str]) -> int:
    print("$ " + " ".join(command))
    completed = subprocess.run(command, cwd=ROOT, env=env, check=False)
    return completed.returncode


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--all", action="store_true", help="run every collected suite instead of the strict contract/UI suites")
    parser.add_argument("--no-sync", action="store_true", help="run pytest only")
    parser.add_argument("--artifacts", type=Path, default=ROOT / "artifacts", help="report directory")
    args = parser.parse_args()

    artifacts = args.artifacts if args.artifacts.is_absolute() else ROOT / args.artifacts
    allure_dir = artifacts / "allure"
    results_json = artifacts / "automation_results.json"
    junit_xml = artifacts / "junit.xml"
    artifacts.mkdir(parents=True, exist_ok=True)
    # 跑批留痕：先备份上一轮结果（防"本轮没跑却拿旧结果过门禁"）
    backup_dir = artifacts / "history" / time.strftime("%Y%m%d-%H%M%S")
    for old in (results_json, junit_xml):
        if old.exists():
            backup_dir.mkdir(parents=True, exist_ok=True)
            shutil.copy2(old, backup_dir / old.name)

    env = os.environ.copy()
    env["RESULTS_JSON"] = str(results_json)
    env["JUNIT_XML"] = str(junit_xml)
    env["ARTIFACT_DIR"] = str(artifacts)
    env["RUN_ID"] = f"{time.strftime('%Y%m%d-%H%M%S')}-{os.getpid()}"
    env["RUN_STARTED_AT"] = datetime.datetime.now().isoformat(timespec="seconds")
    test_targets = ["tests"] if args.all else ["tests/contract", "tests/ui"]
    pytest_command = [
        sys.executable, "-m", "pytest", *test_targets,
        "--junitxml", str(junit_xml), "--alluredir", str(allure_dir),
    ]
    pytest_status = run(pytest_command, env=env)
    print(f"RUN_ID: {env['RUN_ID']}")

    if not args.no_sync:
        results_command = [sys.executable, "scripts/zentao_sync.py", "sync-results", "--results", str(results_json)]
        result_status = run(results_command, env=env)
        if result_status != 0:
            print(f"ZenTao result synchronization failed with exit code {result_status}", file=sys.stderr)
        bug_command = [
            sys.executable, "scripts/zentao_sync.py", "create-bugs",
            "--results", str(results_json), "--bug-manifest", str(artifacts / "zentao_bug_manifest.json"),
        ]
        bug_status = run(bug_command, env=env)
        if bug_status != 0:
            print(f"ZenTao Bug synchronization failed with exit code {bug_status}", file=sys.stderr)

    print(f"pytest exit code: {pytest_status}")
    print(f"JUnit XML: {junit_xml}")
    print(f"Allure raw results: {allure_dir}")
    print(f"Automation results: {results_json}")
    return pytest_status


if __name__ == "__main__":
    raise SystemExit(main())
