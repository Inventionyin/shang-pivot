#!/usr/bin/env python3
"""汇总本轮全部 Allure 结果，重写 results.json / run-manifest.json。

API 与 UI 分多次 pytest 运行时，每次 sessionfinish 都会覆盖这两个文件；
生成报告前调用本脚本，按 nodeid 取最后一次尝试，得到完整的一轮口径。
"""
from __future__ import annotations

import json
import os
import subprocess
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ALLURE = ROOT / "artifacts" / "allure"
STATUS_MAP = {"passed": "passed", "failed": "failed", "broken": "failed", "skipped": "skipped"}


def main() -> None:
    latest: dict[str, dict] = {}
    for file in ALLURE.glob("*-result.json"):
        try:
            data = json.loads(file.read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001
            continue
        labels = {item.get("name"): item.get("value") for item in (data.get("labels") or [])}
        nodeid = labels.get("pytest_nodeid")
        if not nodeid:
            continue
        is_ui = str(nodeid).startswith("tests/ui/") or "test_ui_purchase_flow" in str(nodeid)
        browser = ""
        if is_ui:
            browser = next(
                (str(param.get("value") or "").strip("'\"")
                 for param in data.get("parameters") or []
                 if param.get("name") == "browser_name"),
                "",
            )
        start = data.get("start") or 0
        if nodeid in latest and (latest[nodeid].get("start") or 0) > start:
            continue
        latest[nodeid] = {
            "nodeid": nodeid,
            "outcome": STATUS_MAP.get(data.get("status"), "failed"),
            "duration_seconds": round(max(0, (data.get("stop") or start) - start) / 1000, 3),
            "start": start,
            "raw_status": data.get("status"),
            "ui": is_ui,
            "browser": browser,
        }

    ui_browsers: dict[str, int] = {}
    for item in latest.values():
        if item.get("ui") and item.get("browser"):
            ui_browsers[item["browser"]] = ui_browsers.get(item["browser"], 0) + 1
    api_tests = sum(1 for item in latest.values() if not item.get("ui"))
    results = sorted(latest.values(), key=lambda item: item["nodeid"])
    counts = {"passed": 0, "failed": 0, "skipped": 0}
    for item in results:
        counts[item["outcome"]] += 1

    git = {}
    try:
        git = {
            "commit": subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT.parent,
                                     capture_output=True, text=True, timeout=5).stdout.strip(),
            "branch": subprocess.run(["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=ROOT.parent,
                                     capture_output=True, text=True, timeout=5).stdout.strip(),
            "dirty": True,
        }
    except Exception:  # noqa: BLE001
        pass

    run_id = os.getenv("RUN_ID") or datetime.now().strftime("%Y%m%d-%H%M%S")
    payload = {
        "exit_status": 0 if counts["failed"] == 0 else 1,
        "run_id": run_id,
        "git": git,
        "case_library_hash": "-",
        "baseline": "-",
        "env": os.getenv("TEST_ENV", "dev"),
        "worker": "final",
        "results": [{key: value for key, value in item.items() if key != "start"} for item in results],
    }
    (ROOT / "artifacts" / "results.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    manifest = {
        "run_id": run_id,
        "worker": "final",
        "started_at": "",
        "finished_at": datetime.now().isoformat(timespec="seconds"),
        "git": git,
        "env": payload["env"],
        "case_library_hash": "-",
        "baseline": "-",
        "results_file": "results.json",
        "counts": counts,
        "total": len(results),
    }
    (ROOT / "artifacts" / "run-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    env_file = ALLURE / "environment.properties"
    browsers = ",".join(sorted(ui_browsers))
    lines = [
        f"Run.ID={run_id}",
        f"Git.Commit={git.get('commit', '-')}",
        f"Git.Branch={git.get('branch', '-')}",
        f"Total.Tests={len(latest)}",
        f"API.Tests={api_tests}",
        f"API.Base={os.getenv('MALL_BASE_URL', 'http://127.0.0.1:8086')}",
    ]
    if browsers:
        lines += [
            f"UI.Browsers={browsers}",
            "UI.Cases=" + ",".join(f"{name}:{count}" for name, count in sorted(ui_browsers.items())),
            f"UI.Parallel={len(ui_browsers)} browsers concurrently (xdist)",
        ]
    env_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"[done] 汇总 {len(results)} 条结果：{counts} -> run {run_id}")


if __name__ == "__main__":
    main()
