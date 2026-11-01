#!/usr/bin/env python3
"""UI 多浏览器结果矩阵：读取 Allure 结果，按「用例 × 浏览器」聚合。

输出：<report>/ext/data/ui.json
  summary.browsers  每个浏览器的通过/失败/耗时
  rows              每条 UI 用例在各浏览器的结果（含 Allure 深链）

浏览器的识别：pytest-playwright 写入参数 browser_name；nodeid 的 [firefox]/[webkit] 后缀兜底。
"""
from __future__ import annotations

import json
import re
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ALLURE_DIR = ROOT / "artifacts" / "allure"
BROWSERS = ("chromium", "firefox", "webkit")
CASE_ID = re.compile(r"^([A-Z][A-Z0-9]*(?:-[A-Z][A-Z0-9]*)*-\d+)")


def ui_result_files() -> list[Path]:
    return sorted(ALLURE_DIR.glob("*-result.json"))


def browser_of(data: dict) -> str:
    for param in data.get("parameters") or []:
        if param.get("name") == "browser_name":
            value = str(param.get("value") or "").strip("'\"")
            if value:
                return value
    nodeid = (data.get("labels") or [{}])[0].get("value", "") if data.get("labels") else ""
    for name in BROWSERS:
        if f"[{name}]" in str(nodeid) or f"［{name}］" in str(data.get("name") or ""):
            return name
    return "chromium"


def logical_nodeid(nodeid: str) -> str:
    # [firefox] → 去掉；[firefox-users] → [users]（浏览器前缀 + 其他参数）
    return re.sub(
        r"\[(chromium|firefox|webkit)(?:-([^\]]+))?\]$",
        lambda match: f"[{match.group(2)}]" if match.group(2) else "",
        nodeid,
    )


def logical_name(name: str) -> str:
    return re.sub(
        r"［(chromium|firefox|webkit)(?:-([^］]+))?］",
        lambda match: f"［{match.group(2)}］" if match.group(2) else "",
        name,
    ).strip()


def main(report_dir: Path) -> None:
    latest: dict[tuple[str, str], dict] = {}
    for file in ui_result_files():
        try:
            data = json.loads(file.read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001
            continue
        labels = {l.get("name"): l.get("value") for l in (data.get("labels") or [])}
        nodeid = labels.get("pytest_nodeid") or ""
        if not (str(nodeid).startswith("tests/ui/") or "test_ui_purchase_flow" in str(nodeid)):
            continue
        browser = browser_of(data)
        key = (logical_nodeid(str(nodeid)), browser)
        start = data.get("start") or 0
        if key in latest and (latest[key].get("start") or 0) > start:
            continue
        latest[key] = {
            "start": start,
            "status": data.get("status") or "unknown",
            "durationMs": max(0, (data.get("stop") or start) - start),
            "message": ((data.get("statusDetails") or {}).get("message") or "")[:200],
            "name": data.get("name") or "",
            "uuid": data.get("uuid") or "",
        }

    rows: dict[str, dict] = {}
    for (nodeid, browser), result in latest.items():
        row = rows.setdefault(nodeid, {"nodeid": nodeid, "name": "", "caseId": "", "browsers": {}})
        name = logical_name(result["name"])
        row["name"] = row["name"] or name
        match = CASE_ID.match(name)
        if match:
            row["caseId"] = match.group(1)
        row["browsers"][browser] = result

    summary = {"cases": len(rows), "browsers": []}
    for browser in BROWSERS:
        items = [row["browsers"][browser] for row in rows.values() if browser in row["browsers"]]
        if not items:
            continue
        counts = {status: sum(1 for item in items if item["status"] == status)
                  for status in ("passed", "failed", "broken", "skipped")}
        summary["browsers"].append({
            "name": browser,
            "total": len(items),
            "passed": counts["passed"],
            "failed": counts["failed"],
            "broken": counts["broken"],
            "skipped": counts["skipped"],
            "passRate": round(counts["passed"] / len(items) * 100, 1),
            "durationMs": sum(item["durationMs"] for item in items),
        })

    payload = {
        "generatedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "summary": summary,
        "rows": sorted(rows.values(), key=lambda row: (row["caseId"], row["nodeid"])),
    }
    out = report_dir / "ext" / "data" / "ui.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8")
    rates = ", ".join(f"{b['name']} {b['passRate']}%" for b in summary["browsers"]) or "无 UI 结果"
    print(f"[done] UI 矩阵 {len(rows)} 条用例（{rates}）-> {out}")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report-dir", default=str(ROOT / "artifacts" / "allure-report"))
    main(Path(parser.parse_args().report_dir))
