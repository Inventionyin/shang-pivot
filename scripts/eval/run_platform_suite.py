"""Run one platform suite against its targets (stdlib only).

Credentials from environment only:
  PLATFORM_BASE_URL, PLATFORM_EMAIL, PLATFORM_PASSWORD, PLATFORM_PROJECT
  PLATFORM_TARGETS (comma separated), PLATFORM_SUITE

Usage:
  python scripts/eval/run_platform_suite.py --suite v36-refcart-auto
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from typing import Any


def request(base: str, method: str, path: str, token: str = "", project: str = "",
            body: dict[str, Any] | None = None) -> Any:
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json", "X-Project-Id": project}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(f"{base}{path}", data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=600) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"HTTP {exc.code} {path}: {exc.read()[:300]!r}") from exc
    if isinstance(payload, dict) and payload.get("code") not in (None, 0, "0"):
        raise RuntimeError(f"platform error {path}: {payload.get('message') or payload.get('msg')}")
    return payload.get("data") if isinstance(payload, dict) else payload


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="运行平台上的指定 suite")
    parser.add_argument("--suite", default=os.getenv("PLATFORM_SUITE", "v36-refcart-auto"))
    parser.add_argument("--base-url", default=os.getenv("PLATFORM_BASE_URL", ""))
    parser.add_argument("--email", default=os.getenv("PLATFORM_EMAIL", ""))
    parser.add_argument("--password", default=os.getenv("PLATFORM_PASSWORD", ""))
    parser.add_argument("--project", default=os.getenv("PLATFORM_PROJECT", "ecommerce"))
    parser.add_argument("--target", action="append", default=[])
    parser.add_argument("--limit", type=int, default=0)
    args = parser.parse_args(argv)
    if not args.base_url or not args.email or not args.password:
        print("缺少平台凭据：PLATFORM_BASE_URL/PLATFORM_EMAIL/PLATFORM_PASSWORD", file=sys.stderr)
        return 2
    targets = args.target or [x.strip() for x in os.getenv("PLATFORM_TARGETS", "").split(",") if x.strip()]
    if not targets:
        print("缺少目标：--target 或 PLATFORM_TARGETS", file=sys.stderr)
        return 2

    base = args.base_url.rstrip("/")
    token = request(base, "POST", "/api/v1/auth/login", project=args.project,
                    body={"email": args.email, "password": args.password})["accessToken"]
    cases = request(base, "GET", "/api/v1/agent-test/cases", token, args.project) or []
    by_key: dict[tuple[str, str], dict[str, Any]] = {}
    for case in cases:
        sid = str((case.get("payload") or {}).get("sourceId") or "")
        if sid:
            by_key[(str(case.get("targetId") or ""), sid)] = case

    failures: list[str] = []
    total = 0
    for target in targets:
        selected = [
            case for (t, sid), case in sorted(by_key.items())
            if t == target and str((case.get("payload") or {}).get("datasetVersion") or "") == args.suite
        ]
        if args.limit:
            selected = selected[:args.limit]
        print(f"[platform-run] target={target} suite={args.suite} cases={len(selected)}")
        for case in selected:
            total += 1
            sid = str((case.get("payload") or {}).get("sourceId") or "")
            data = request(base, "POST", "/api/v1/agent-test/runs", token, args.project,
                           {"caseId": case["id"], "targetId": target})
            run = (data or {}).get("run") or {}
            status = str(run.get("status") or "error")
            print(f"  {sid} {status}")
            if status != "pass":
                failures.append(f"{target}/{sid}: {status} {run.get('error') or ''}")
    print(f"[platform-run] total={total} failed={len(failures)}")
    for item in failures:
        print(f"  FAIL {item}")
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
