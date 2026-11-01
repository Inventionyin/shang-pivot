#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""重写已上传的自动化 Bug（标题/正文按用例规范）——一次性修复脚本，可复用。

用法：ZENTAO_USER=... ZENTAO_PASSWORD=... python3 scripts/rewrite_uploaded_bugs.py [--dry-run]
"""
import argparse
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from zentao_sync import ZenTaoClient, compose_bug_content, _case_rows  # noqa: E402

# 已上传的自动化 Bug → 重写目标（标题人工校准，正文由生成器统一）
TARGETS = [
    {
        "bug_id": 4, "stable_id": "BUS-PRD-002",
        "nodeid": "tests/functional/test_products.py::TestProductDetail::test_detail_not_found",
        "title": "[自动化] 商品详情_不存在ID_实际返回500（预期404提示资源不存在）",
    },
    {
        "bug_id": 3, "stable_id": "BUS-PRD-001",
        "nodeid": "tests/e2e/test_shopping_flow.py::TestShoppingFlow::test_product_search_filter",
        "title": "[自动化] 商品列表_无效关键词搜索_实际返回未过滤数据（预期空结果）",
    },
]
BASE_URL = os.getenv("BASE_URL", "http://103.236.91.136:60080/api")


def longrepr_of(nodeid: str) -> str:
    safe = nodeid.replace("::", "_").replace("/", "_")
    path = ROOT / "artifacts" / "run" / "failures" / f"{safe}.json"
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8")).get("longrepr", "")
    return ""


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    user, pwd = os.getenv("ZENTAO_USER"), os.getenv("ZENTAO_PASSWORD")
    if not user or not pwd:
        raise SystemExit("Set ZENTAO_USER/ZENTAO_PASSWORD")
    client = ZenTaoClient(os.getenv("ZENTAO_BASE_URL", "http://YOUR_SERVER_IP:60082"), user, pwd)
    rows = _case_rows()
    for t in TARGETS:
        _, body = compose_bug_content(t["stable_id"], t["nodeid"], longrepr_of(t["nodeid"]), rows, BASE_URL)
        print("=" * 78)
        print(f"Bug #{t['bug_id']}｜新标题：{t['title']}")
        print("-" * 78)
        print(body[:900] + ("…" if len(body) > 900 else ""))
        if args.dry_run:
            continue
        result = client.update_bug(t["bug_id"], title=t["title"], steps=body,
                                   comment="由自动化失败自动创建；已按测试用例规范重写标题与正文（前置/步骤/预期/实际/运行信息）。")
        print(f"→ 更新结果: {result}")
    if not args.dry_run:
        # 回读验证
        for t in TARGETS:
            page = client._request_with_retry("GET", client.base + f"/bug-view-{t['bug_id']}.html", timeout=30).text
            ok_title = t["title"] in page
            ok_body = "【前置条件】" in page and "【实际结果】" in page
            print(f"回读 Bug #{t['bug_id']}: 标题={'✓' if ok_title else '✗'} 正文={'✓' if ok_body else '✗'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
