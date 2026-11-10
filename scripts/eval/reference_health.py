"""Reference-cart health snapshot from trace tables (read-only).

Usage:
  python scripts/eval/reference_health.py --out /tmp/reference-health.md --since-days 14
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import os
import subprocess
import sys
from pathlib import Path

SQL = """
SELECT
  (SELECT count(*) FROM customerservice.agent_run
    WHERE started_at > NOW() - (%s || ' days')::interval) AS runs,
  (SELECT count(*) FROM customerservice.agent_run
    WHERE started_at > NOW() - (%s || ' days')::interval AND status = 'FAILED') AS failed,
  (SELECT count(*) FROM customerservice.agent_run
    WHERE started_at > NOW() - (%s || ' days')::interval AND status = 'HANDOFF') AS handoff,
  (SELECT count(*) FROM customerservice.agent_run
    WHERE started_at > NOW() - (%s || ' days')::interval AND status = 'NEED_CONFIRMATION') AS confirm,
  (SELECT count(*) FROM customerservice.tool_call
    WHERE created_at > NOW() - (%s || ' days')::interval AND tool_name = 'addToCart') AS cart_calls,
  (SELECT count(*) FROM customerservice.tool_call
    WHERE created_at > NOW() - (%s || ' days')::interval AND tool_name = 'addToCart'
      AND status = 'SUCCESS') AS cart_success,
  (SELECT count(*) FROM customerservice.pending_action
    WHERE created_at > NOW() - (%s || ' days')::interval AND tool_name = 'addToCart') AS cart_pending,
  (SELECT count(*) FROM customerservice.retrieval
    WHERE created_at > NOW() - (%s || ' days')::interval) AS retrievals,
  (SELECT count(*) FROM customerservice.retrieval
    WHERE created_at > NOW() - (%s || ' days')::interval AND hit = FALSE) AS retrieval_miss
"""


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="指代加购健康快照")
    parser.add_argument("--out", required=True)
    parser.add_argument("--since-days", type=int, default=int(os.getenv("TRACE_SINCE_DAYS", "14")))
    parser.add_argument("--host", default=os.getenv("DB_HOST", "127.0.0.1"))
    parser.add_argument("--port", default=os.getenv("DB_PORT", "5432"))
    parser.add_argument("--database", default=os.getenv("DB_NAME", "shang"))
    parser.add_argument("--user", default=os.getenv("DB_USERNAME", "shang"))
    parser.add_argument("--password", default=os.getenv("DB_PASSWORD", ""))
    args = parser.parse_args(argv)
    env = dict(os.environ)
    if args.password:
        env["PGPASSWORD"] = args.password
    days = str(max(1, args.since_days))
    sql = SQL % tuple([days] * 9)
    cmd = ["psql", "-h", args.host, "-p", str(args.port), "-U", args.user,
           "-d", args.database, "--csv", "-v", "ON_ERROR_STOP=1", "-c", sql]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, env=env, timeout=60)
    except FileNotFoundError:
        print("找不到 psql", file=sys.stderr)
        return 2
    if result.returncode != 0:
        print(result.stderr.strip(), file=sys.stderr)
        return 2
    row = next(csv.DictReader(io.StringIO(result.stdout)), {})
    def num(key: str) -> int:
        try:
            return int(row.get(key) or 0)
        except ValueError:
            return 0
    runs = num("runs")
    cart_calls = num("cart_calls")
    retrievals = num("retrievals")
    data = {
        "sinceDays": args.since_days,
        "runs": runs,
        "failed": num("failed"),
        "handoff": num("handoff"),
        "needConfirmation": num("confirm"),
        "cartCalls": cart_calls,
        "cartSuccess": num("cart_success"),
        "cartPending": num("cart_pending"),
        "retrievals": retrievals,
        "retrievalMiss": num("retrieval_miss"),
    }
    data["failedRate"] = round(data["failed"] / runs, 4) if runs else 0
    data["cartSuccessRate"] = round(data["cartSuccess"] / cart_calls, 4) if cart_calls else 0
    data["retrievalMissRate"] = round(data["retrievalMiss"] / retrievals, 4) if retrievals else 0
    report = "\n".join([
        "# 指代加购健康快照",
        "",
        f"- 窗口：最近 {args.since_days} 天",
        f"- Agent 运行：{runs}",
        f"- 失败：{data['failed']}（{data['failedRate']:.1%}）",
        f"- 转人工：{data['handoff']}",
        f"- 待确认：{data['needConfirmation']}",
        f"- 加购调用：{cart_calls}，成功 {data['cartSuccess']}（{data['cartSuccessRate']:.1%}），待确认 {data['cartPending']}",
        f"- 检索：{retrievals}，无命中 {data['retrievalMiss']}（{data['retrievalMissRate']:.1%}）",
        "",
        "> 仅观测指标；商品归因正确率以 reference-cart 平台套件为准。",
    ]) + "\n"
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(report, encoding="utf-8")
    print(report)
    print(json.dumps(data, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
