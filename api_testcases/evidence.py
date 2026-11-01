"""Failure evidence writer for API tests.

Artifacts contain redacted request/response data and a PNG snapshot generated
from the failure summary.  The PNG is intentionally a text evidence card: API
tests have no browser viewport, while ZenTao still receives a stable image
attachment for each failed case.
"""
from __future__ import annotations

import json
import os
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


SECRET_KEYS = re.compile(r"(authorization|password|token|cookie|set-cookie|secret)", re.I)


def redact(value: Any) -> Any:
    if isinstance(value, dict):
        return {k: "[REDACTED]" if SECRET_KEYS.search(str(k)) else redact(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [redact(v) for v in value]
    text = str(value)
    return "[REDACTED]" if SECRET_KEYS.search(text) and len(text) > 20 else text


def _response_payload(response: Any) -> dict[str, Any] | None:
    if response is None:
        return None
    try:
        body: Any = response.json()
    except Exception:
        body = response.text[:12000]
    request = getattr(response, "request", None)
    return {
        "status": response.status_code,
        "reason": response.reason,
        "url": response.url,
        "elapsed_seconds": response.elapsed.total_seconds(),
        "request": {
            "method": getattr(request, "method", None),
            "url": getattr(request, "url", None),
            "headers": redact(dict(getattr(request, "headers", {}) or {})),
            "body": redact(getattr(request, "body", None)),
        },
        "response_headers": redact(dict(response.headers)),
        "body": redact(body),
    }


def write_failure_evidence(nodeid: str, report: Any, response: Any = None) -> Path:
    root = Path(os.getenv("ARTIFACT_DIR", "artifacts")) / "failures"
    root.mkdir(parents=True, exist_ok=True)
    safe_id = re.sub(r"[^A-Za-z0-9_.-]+", "_", nodeid)
    stamp = datetime.now(timezone.utc).isoformat()
    payload = {
        "case_id": nodeid,
        "phase": getattr(report, "when", "call"),
        "outcome": getattr(report, "outcome", "failed"),
        "timestamp": stamp,
        "longrepr": str(getattr(report, "longrepr", "")),
        "request_response": _response_payload(response),
    }
    json_path = root / f"{safe_id}.json"
    json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    steps = [
        f"1. 执行 pytest 用例：{nodeid}",
        "2. 记录请求方法、脱敏请求头、URL、请求体和响应体",
        "3. 对照用例预期断言；失败原因为：" + str(getattr(report, "longrepr", "")),
        "4. 将 JSON 证据和 PNG 证据上传至禅道 Bug 附件",
    ]
    (root / f"{safe_id}.steps.txt").write_text("\n".join(steps), encoding="utf-8")
    try:
        from PIL import Image, ImageDraw
        image = Image.new("RGB", (1400, 620), "white")
        draw = ImageDraw.Draw(image)
        lines = ["API TEST FAILURE EVIDENCE", f"Case: {nodeid}", f"Time: {stamp}",
                 "Request/response details are in the adjacent redacted JSON file."]
        lines += str(getattr(report, "longrepr", ""))[:1800].splitlines()[:12]
        draw.multiline_text((32, 32), "\n".join(lines), fill="black", spacing=12)
        image.save(root / f"{safe_id}.png")
    except Exception as exc:
        (root / f"{safe_id}.png.error.txt").write_text(str(exc), encoding="utf-8")
    return json_path
