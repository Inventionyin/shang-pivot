#!/usr/bin/env python3
"""Apply approved case revisions with keyword-preserving verification."""
from __future__ import annotations

import argparse
import html
import json
import re
import time
from pathlib import Path

from zentao_sync import ZenTaoClient


def view_text(client: ZenTaoClient, case_id: int) -> str:
    url = client.base + f"/testcase-view-{case_id}.html"
    response = client._request_with_retry("GET", url, timeout=30)
    response.raise_for_status()
    text = html.unescape(response.text)
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", text))


def apply(client: ZenTaoClient, plan: dict, ids: list[str], manifest_path: Path) -> dict:
    by_id = {case["case_id"]: case for case in plan["cases"]}
    mapping = client.case_id_map()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {}
    failures = {}
    for index, stable_id in enumerate(ids, 1):
        if stable_id not in by_id:
            raise ValueError(f"revision missing: {stable_id}")
        remote = mapping.get(stable_id)
        if not remote:
            raise ValueError(f"keyword not found: {stable_id}")
        case = by_id[stable_id]
        expected_keyword = f"API-AUTO,{stable_id}"
        if remote.get("keywords") != expected_keyword:
            raise ValueError(f"keyword changed before edit: {stable_id}: {remote.get('keywords')}")
        try:
            client._edit_case(case, remote)
            time.sleep(0.15)
            refreshed = client.case_records().get(stable_id)
            if not refreshed or refreshed.get("keywords") != expected_keyword:
                raise RuntimeError(f"keyword verification failed after edit: {stable_id}")
            plain = view_text(client, int(remote["case_id"]))
            checks = {
                "title": case["title"] in plain,
                "precondition": case["precondition"] in plain,
                "steps": case["steps"].split("步骤：", 1)[-1].strip()[:30] in plain,
                "expected": case["expected_code"] in plain,
                "keyword": refreshed.get("keywords") == expected_keyword,
            }
            if not all(checks.values()):
                raise RuntimeError(f"view verification failed: {stable_id}: {checks}")
            manifest[stable_id] = {
                "status": "repaired",
                "case_id": int(remote["case_id"]),
                "old_title": remote.get("title", ""),
                "new_title": case["title"],
                "keywords": refreshed.get("keywords"),
                "checks": checks,
                "updated_at": int(time.time()),
            }
        except Exception as exc:
            failures[stable_id] = str(exc)
            manifest[stable_id] = {"status": "failed", "case_id": int(remote["case_id"]), "error": str(exc), "updated_at": int(time.time())}
            print(f"failed {index}/{len(ids)} {stable_id}: {exc}")
        manifest_path.parent.mkdir(parents=True, exist_ok=True)
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
        if index % 20 == 0:
            ok = sum(1 for x in manifest.values() if x.get("status") == "repaired")
            print(f"progress={index}/{len(ids)} repaired={ok} failed={len(failures)}")
    if failures:
        raise RuntimeError(f"batch completed with {len(failures)} failures: {json.dumps(failures, ensure_ascii=False)}")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan", type=Path, required=True)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--ids", nargs="+")
    group.add_argument("--all", action="store_true", help="apply every case in the revision plan")
    parser.add_argument("--exclude", nargs="*", default=[], help="stable IDs excluded when using --all")
    parser.add_argument("--manifest", type=Path, default=Path("artifacts/case-pilot-manifest.json"))
    args = parser.parse_args()
    import os
    client = ZenTaoClient(os.environ.get("ZENTAO_BASE_URL", "http://YOUR_SERVER_IP:60082"), os.environ["ZENTAO_USER"], os.environ["ZENTAO_PASSWORD"], int(os.environ.get("ZENTAO_PRODUCT_ID", "1")))
    plan = json.loads(args.plan.read_text(encoding="utf-8"))
    ids = args.ids or [case["case_id"] for case in plan["cases"] if case["case_id"] not in set(args.exclude)]
    manifest = apply(client, plan, ids, args.manifest)
    print(json.dumps({"requested": len(ids), "manifest": str(args.manifest), "failed": [key for key in ids if manifest.get(key, {}).get("status") == "failed"]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
