"""Mine failed / handoff / ambiguous / retrieval-miss runs into candidate cases.

Usage:
  python scripts/eval/mine_failure_candidates.py \
    --out /tmp/reference-candidates.jsonl --since-days 14

Read-only. Output must be human-reviewed before entering a Golden suite.
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

SQL = """
SELECT r.id AS run_id,
       COALESCE(r.intent, '') AS intent,
       COALESCE(r.agent, '') AS agent,
       COALESCE(r.status, '') AS status,
       COALESCE(r.error, '') AS error,
       COALESCE((SELECT m.content
                 FROM customerservice.message m
                 WHERE m.session_id = r.session_id AND m.role = 'USER'
                   AND m.created_at <= r.started_at
                 ORDER BY m.created_at DESC LIMIT 1), '') AS user_text,
       COALESCE((SELECT left(m.content, 160)
                 FROM customerservice.message m
                 WHERE m.session_id = r.session_id AND m.role = 'ASSISTANT'
                   AND m.created_at < r.started_at
                 ORDER BY m.created_at DESC LIMIT 1), '') AS prev_assistant,
       COALESCE((SELECT string_agg(t.tool_name || ':' || t.status, ',' ORDER BY t.id)
                 FROM customerservice.tool_call t WHERE t.run_id = r.id), '') AS tools,
       COALESCE((SELECT string_agg(q.query || ':' || q.hit, ' | ' ORDER BY q.id)
                 FROM customerservice.retrieval q WHERE q.run_id = r.id), '') AS retrieval,
       COALESCE((SELECT string_agg(left(t.error_code, 40), ',')
                 FROM customerservice.tool_call t
                 WHERE t.run_id = r.id AND t.status <> 'SUCCESS'), '') AS tool_errors,
       COALESCE((SELECT string_agg(f.rating || ':' || COALESCE(f.reason, ''), ',' ORDER BY f.id)
                 FROM customerservice.message_feedback f
                 WHERE f.run_id = r.id AND f.deleted = FALSE), '') AS feedback
FROM customerservice.agent_run r
WHERE r.started_at > NOW() - (%s || ' days')::interval
  AND (
      r.status IN ('FAILED', 'HANDOFF')
      OR EXISTS (SELECT 1 FROM customerservice.tool_call t
                 WHERE t.run_id = r.id AND t.status <> 'SUCCESS')
      OR EXISTS (SELECT 1 FROM customerservice.retrieval q
                 WHERE q.run_id = r.id AND q.hit = FALSE)
      OR EXISTS (SELECT 1 FROM customerservice.message_feedback f
                 WHERE f.run_id = r.id AND f.rating = 'DOWN' AND f.deleted = FALSE)
      OR EXISTS (SELECT 1 FROM customerservice.message m
                 WHERE m.session_id = r.session_id AND m.role = 'ASSISTANT'
                   AND m.created_at < r.started_at
                   AND m.created_at > r.started_at - INTERVAL '10 minutes'
                   AND (m.content LIKE '%%请回复序号%%'
                        OR m.content LIKE '%%您指的是哪一款%%'
                        OR m.content LIKE '%%没有可操作的商品%%'))
  )
ORDER BY r.started_at DESC
LIMIT %s
"""


def mask_pii(text: str) -> str:
    text = re.sub(r"(?<!\d)1[3-9]\d{9}(?!\d)", "1**********", text or "")
    text = re.sub(r"(?<![0-9A-Za-z])\d{17}[\dXx](?![0-9A-Za-z])", "***", text)
    return text


def psql(dsn_args: list[str], sql: str, env: dict[str, str]) -> str:
    cmd = ["psql", *dsn_args, "--csv", "-v", "ON_ERROR_STOP=1", "-c", sql]
    result = subprocess.run(cmd, capture_output=True, text=True, env=env, timeout=60)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "psql failed")
    return result.stdout


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="从生产 trace 挖掘评测候选")
    parser.add_argument("--out", required=True)
    parser.add_argument("--since-days", type=int, default=int(os.getenv("TRACE_SINCE_DAYS", "14")))
    parser.add_argument("--limit", type=int, default=int(os.getenv("TRACE_LIMIT", "200")))
    parser.add_argument("--host", default=os.getenv("DB_HOST", "127.0.0.1"))
    parser.add_argument("--port", default=os.getenv("DB_PORT", "5432"))
    parser.add_argument("--database", default=os.getenv("DB_NAME", "shang"))
    parser.add_argument("--user", default=os.getenv("DB_USERNAME", "shang"))
    parser.add_argument("--password", default=os.getenv("DB_PASSWORD", ""))
    args = parser.parse_args(argv)

    env = dict(os.environ)
    if args.password:
        env["PGPASSWORD"] = args.password
    dsn = ["-h", args.host, "-p", str(args.port), "-U", args.user, "-d", args.database]
    sql = SQL % (str(max(1, args.since_days)), str(max(1, args.limit)))
    try:
        raw = psql(dsn, sql, env)
    except FileNotFoundError:
        print("找不到 psql，请安装 postgresql-client", file=sys.stderr)
        return 2
    except Exception as exc:  # noqa: BLE001
        print(f"trace 查询失败：{exc}", file=sys.stderr)
        return 2

    rows: list[dict[str, object]] = []
    for row in csv.DictReader(io.StringIO(raw)):
        run_id = str(row.get("run_id") or "").strip()
        if not run_id:
            continue
        status = str(row.get("status") or "")
        tool_errors = str(row.get("tool_errors") or "")
        retrieval = str(row.get("retrieval") or "")
        feedback = str(row.get("feedback") or "")
        prev_assistant = str(row.get("prev_assistant") or "")
        reasons: list[str] = []
        if status in ("FAILED", "HANDOFF"):
            reasons.append(f"status={status}")
        if tool_errors:
            reasons.append(f"tool_error={tool_errors}")
        if ":f" in retrieval or ":false" in retrieval.lower():
            reasons.append("retrieval_miss")
        if "DOWN" in feedback.upper():
            reasons.append(f"negative_feedback={feedback}")
        if "请回复序号" in prev_assistant or "您指的是哪一款" in prev_assistant:
            reasons.append("clarify_without_followup")
        if "没有可操作的商品" in prev_assistant:
            reasons.append("no_context_claim")
        rows.append({
            "sourceId": f"C-{run_id}",
            "userText": mask_pii(str(row.get("user_text") or "")),
            "prevAssistant": mask_pii(prev_assistant),
            "intent": str(row.get("intent") or ""),
            "agent": str(row.get("agent") or ""),
            "status": status,
            "error": mask_pii(str(row.get("error") or "")),
            "tools": str(row.get("tools") or ""),
            "retrieval": retrieval,
            "feedback": feedback,
            "reason": ",".join(reasons) or "candidate",
            "capturedAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
            "datasetVersion": "candidate",
            "note": "人工复核后再入集；不要直接自动导入平台",
        })

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in rows) + "\n", encoding="utf-8")
    print(f"[trace-candidates] wrote {out} count={len(rows)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
