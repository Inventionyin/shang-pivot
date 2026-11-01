#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""三件套对账器（流程 ①/④ 的守门人，同时是夜间 CI 入口）。

对账三本账，任一差集非 0 且未登记豁免即失败：
  1) 接口账：源码端点 ↔ OpenAPI ↔ 《接口文档》 三方集合
  2) 需求账：《需求文档》需求编号 ↔ 用例备注引用
  3) 词典账：《领域词典》机器可读词表 ↔ 用例断言/预期中的大写枚举

豁免登记：artifacts/exemptions.json
   {"interface": {"METHOD /path": "理由"}, "requirement": {"INF-03": "理由"}, "dict": {"TOKEN": "理由"}}

用法：
  python3 scripts/reconcile.py            # 对账 + 出报告
  python3 scripts/reconcile.py --json     # 仅输出机器可读结果
退出码：0=全部对齐（或已登记豁免）；1=存在未登记差集
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT))

import case_lint as L  # noqa: E402
from build_api_doc import collect_source_endpoints  # noqa: E402

EXEMPTIONS = ROOT / "artifacts" / "exemptions.json"


def load_exemptions() -> dict:
    if EXEMPTIONS.exists():
        return json.loads(EXEMPTIONS.read_text(encoding="utf-8"))
    return {}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--mdout", type=Path, default=ROOT / "artifacts" / "reconcile-report.md")
    args = ap.parse_args()
    ex = load_exemptions()

    repo_root = ROOT.parent
    src = {f"{e['method']} {L.norm_path(e['path'])}" for e in collect_source_endpoints(repo_root)}
    openapi = json.loads((repo_root / "docs" / "openapi-v3-api-docs.json").read_text(encoding="utf-8"))
    oa = {f"{m.upper()} {L.norm_path(p)}" for p, item in openapi["paths"].items()
          for m in item if m.upper() in ("GET", "POST", "PUT", "DELETE", "PATCH")}
    doc = L.parse_endpoint_catalog(L.DEFAULT_DOC)

    rows = L.parse_inventory(L.DEFAULT_INV)
    req_ids = L.parse_req_ids(L._governance_doc("需求文档.md", "REQ_DOC_PATH"))
    cited = set()
    for r in rows:
        cited |= {m.group(0) for m in __import__("re").finditer(
            r"\b(?:PRD|RBAC|STK|ME|MALL|CFG|CUS|INF)-\d{2}\b", r["notes"])}
    dict_words = L.parse_domain_dict(L.DEFAULT_DICT)
    used_enums = set()
    for r in rows:
        for f in ("expected_code", "assertions"):
            used_enums |= set(L.ENUM_TOKEN.findall(r[f]))

    iface_gap = sorted((src ^ oa) | (src - doc))
    iface_gap_ok = [g for g in iface_gap if g in ex.get("interface", {})]
    req_gap = sorted(req_ids - cited)
    req_gap_ok = [g for g in req_gap if g in ex.get("requirement", {})]
    dict_gap = sorted(w for w in used_enums if w not in dict_words and w not in L.ENUM_STOPWORDS)
    dict_gap_ok = [g for g in dict_gap if g in ex.get("dict", {})]

    result = {
        "interface": {"source": len(src), "openapi": len(oa), "doc": len(doc),
                      "unregistered_gap": [g for g in iface_gap if g not in ex.get("interface", {})],
                      "exempted": iface_gap_ok},
        "requirement": {"total": len(req_ids), "cited": len(cited & req_ids),
                        "unregistered_gap": [g for g in req_gap if g not in ex.get("requirement", {})],
                        "exempted": req_gap_ok},
        "dict": {"size": len(dict_words), "used": len(used_enums),
                 "unregistered_gap": [g for g in dict_gap if g not in ex.get("dict", {})],
                 "exempted": dict_gap_ok},
        "inventory_cases": len(rows),
    }
    bad = (result["interface"]["unregistered_gap"] or result["requirement"]["unregistered_gap"]
           or result["dict"]["unregistered_gap"])
    result["verdict"] = "FAIL" if bad else "PASS"

    if args.json:
        print(json.dumps(result, ensure_ascii=False))
    else:
        print(f"①接口账 源码 {len(src)} / OpenAPI {len(oa)} / 文档 {len(doc)} "
              f"| 未登记差集 {len(result['interface']['unregistered_gap'])} | 豁免 {len(iface_gap_ok)}")
        print(f"②需求账 {result['requirement']['cited']}/{result['requirement']['total']} 被引用 "
              f"| 未登记缺口 {len(result['requirement']['unregistered_gap'])} | 豁免 {len(req_gap_ok)}")
        print(f"③词典账 词表 {len(dict_words)} / 用例使用 {len(used_enums)} "
              f"| 未登记词 {len(result['dict']['unregistered_gap'])} | 豁免 {len(dict_gap_ok)}")
        print(f"清单 {len(rows)} 条 | 对账结论：{result['verdict']}")

    lines = [f"# 三件套对账报告（{result['verdict']}）", "",
             f"- 清单用例：{len(rows)}", "",
             "| 账本 | 规模 | 未登记差集 | 已登记豁免 |", "|---|---|---|---|",
             f"| 接口账 | 源码 {len(src)} / OpenAPI {len(oa)} / 文档 {len(doc)} | "
             f"{len(result['interface']['unregistered_gap'])} | {len(iface_gap_ok)} |",
             f"| 需求账 | {len(req_ids)} 条编号 | {len(result['requirement']['unregistered_gap'])} | {len(req_gap_ok)} |",
             f"| 词典账 | 词表 {len(dict_words)} | {len(result['dict']['unregistered_gap'])} | {len(dict_gap_ok)} |", ""]
    for name, key in (("接口", "interface"), ("需求", "requirement"), ("词典", "dict")):
        gap = result[key]["unregistered_gap"]
        exd = result[key]["exempted"]
        if gap:
            lines += [f"## {name}未登记差集（需补用例或登记豁免）", ""] + [f"- `{g}`" for g in gap[:50]] + [""]
        if exd:
            lines += [f"## {name}已登记豁免", ""] + [f"- `{g}` — {ex.get(key, {}).get(g, '')}" for g in exd] + [""]
    args.mdout.parent.mkdir(parents=True, exist_ok=True)
    args.mdout.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
