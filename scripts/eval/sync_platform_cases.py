"""Upsert generated reference-cart-auto cases into the AI test platform.

Credentials come from environment only:
  PLATFORM_BASE_URL, PLATFORM_EMAIL, PLATFORM_PASSWORD, PLATFORM_PROJECT

Usage:
  python scripts/eval/sync_platform_cases.py \
    --dataset /tmp/reference-cart-auto.jsonl \
    --target AT-xxx [--target AT-yyy] [--dry-run]
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

from reference_common import read_jsonl


def request(base: str, method: str, path: str, token: str = "",
            project: str = "", body: dict[str, Any] | None = None) -> Any:
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json", "X-Project-Id": project}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(f"{base}{path}", data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"HTTP {exc.code} {path}: {exc.read()[:300]!r}") from exc
    if isinstance(payload, dict) and payload.get("code") not in (None, 0, "0"):
        raise RuntimeError(f"platform error {path}: {payload.get('message') or payload.get('msg')}")
    return payload.get("data") if isinstance(payload, dict) else payload


def to_platform(row: dict[str, Any], target: str) -> dict[str, Any]:
    expected = row.get("expected") or {}
    expected = {
        "sourceFragments": expected.get("sourceFragments") or [],
        "cardFragments": expected.get("cardFragments") or [],
        "cart": expected.get("cart") or [],
        "answerKeywords": expected.get("answerKeywords") or [],
        "forbidPatterns": expected.get("forbidPatterns") or [],
        "maxSourceCount": expected.get("maxSourceCount"),
        "judge": {"enabled": False, "threshold": 0.7, "steps": []},
    }
    payload = dict(row.get("payload") or {})
    sid = str(row.get("sourceId") or "")
    payload["sourceTags"] = [f"reference:{sid}", f"suite:{payload.get('suite', 'reference-cart-auto')}"]
    return {
        "name": str(row.get("name") or sid)[:200],
        "targetId": target,
        "inputType": "text",
        "command": row.get("command") or "",
        "turns": row.get("turns") or [],
        "payload": payload,
        "expected": expected,
        "checks": row.get("checks") or [],
        "tags": row.get("tags") or [],
        "priority": row.get("priority") or "P0",
        "enabled": bool(row.get("enabled", True)),
        "version": str(row.get("version") or "v36auto")[:10],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="同步自动生成的指代加购用例到平台")
    parser.add_argument("--dataset", default="scripts/eval/reference-cart-auto.jsonl")
    parser.add_argument("--target", action="append", default=[])
    parser.add_argument("--base-url", default=os.getenv("PLATFORM_BASE_URL", ""))
    parser.add_argument("--email", default=os.getenv("PLATFORM_EMAIL", ""))
    parser.add_argument("--password", default=os.getenv("PLATFORM_PASSWORD", ""))
    parser.add_argument("--project", default=os.getenv("PLATFORM_PROJECT", "ecommerce"))
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    if not args.base_url or not args.email or not args.password:
        print("缺少平台凭据：PLATFORM_BASE_URL/PLATFORM_EMAIL/PLATFORM_PASSWORD", file=sys.stderr)
        return 2
    targets = args.target or [x.strip() for x in os.getenv("PLATFORM_TARGETS", "").split(",") if x.strip()]
    if not targets:
        print("缺少目标：--target 或 PLATFORM_TARGETS", file=sys.stderr)
        return 2
    rows = read_jsonl(args.dataset)
    if not rows:
        print("数据集为空", file=sys.stderr)
        return 2
    base = args.base_url.rstrip("/")
    token = request(base, "POST", "/api/v1/auth/login", project=args.project,
                    body={"email": args.email, "password": args.password})["accessToken"]
    existing = request(base, "GET", "/api/v1/agent-test/cases", token, args.project) or []
    by_key = {
        (str(c.get("targetId") or ""), str((c.get("payload") or {}).get("sourceId") or "")): c
        for c in existing
    }
    created = updated = 0
    for target in targets:
        for row in rows:
            sid = str(row.get("sourceId") or "")
            body = to_platform(row, target)
            found = by_key.get((target, sid))
            if args.dry_run:
                print(f"[dry-run] {target} {sid} {'update' if found else 'create'}")
                continue
            if found:
                request(base, "PUT", f"/api/v1/agent-test/cases/{found['id']}", token, args.project, body)
                updated += 1
            else:
                request(base, "POST", "/api/v1/agent-test/cases", token, args.project, body)
                created += 1
    print(f"[platform-sync] targets={targets} rows={len(rows)} created={created} updated={updated}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
