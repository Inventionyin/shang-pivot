#!/usr/bin/env python3
"""知识库检索：python3 scripts/kb_search.py "登录 验证码" [--kind case] [--limit 10]"""
from __future__ import annotations

import argparse
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DB = ROOT / "artifacts" / "knowledge" / "kb.sqlite"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("query")
    parser.add_argument("--kind")
    parser.add_argument("--limit", type=int, default=10)
    args = parser.parse_args()
    if not DB.exists():
        raise SystemExit("知识库不存在：先运行 scripts/build_knowledge_index.py")
    conn = sqlite3.connect(DB)
    sql = (
        "SELECT d.kind, d.title, substr(d.body,1,160), d.git_commit, d.baseline "
        "FROM docs_fts f JOIN docs d ON d.rowid = f.rowid "
        "WHERE docs_fts MATCH ?"
    )
    params: list = [args.query]
    if args.kind:
        sql += " AND d.kind = ?"
        params.append(args.kind)
    sql += " LIMIT ?"
    params.append(args.limit)
    try:
        rows = conn.execute(sql, params).fetchall()
    except sqlite3.OperationalError:
        rows = []
    if not rows:
        # unicode61 把连续中文当作单个 token，词内检索会漏；退回子串匹配
        like = f"%{args.query}%"
        sql = (
            "SELECT kind, title, substr(body,1,160), git_commit, baseline FROM docs "
            "WHERE title LIKE ? OR body LIKE ?"
        )
        params = [like, like]
        if args.kind:
            sql += " AND kind = ?"
            params.append(args.kind)
        sql += " LIMIT ?"
        params.append(args.limit)
        rows = conn.execute(sql, params).fetchall()
    for kind, title, body, commit, baseline in rows:
        print(f"[{kind}] {title}（{commit}/{baseline}）\n    {body}")
    print(f"-- {len(rows)} 条结果")


if __name__ == "__main__":
    main()
