#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""评审工作台生成器（尚品汇版）：产出《评审工作单.xlsx》+《评审指引.md》+ C6 机器台账。

输入：test_cases_inventory.md（唯一内容源）+ artifacts/automation_results.json（最近执行）
      + artifacts/known-failures.json（缺陷/观察项登记表）
输出：artifacts/review/评审工作单.xlsx（说明 / 评审总表 / 用例详情）
      artifacts/review/评审指引.md
      artifacts/review/p0-review.json     ← 发布门禁 C6 直接读这个（p0_total / p0_reviewed / items[].结论）

机制对齐：机器预填事实（最近执行 / 已知缺陷 / 预期摘要），人只填「结论 + 意见」两列。
分层抽样：P0 全评；P1 按域分层随机 30%（固定种子可复现）。

用法：
  python3 scripts/review_workbench.py                     # 生成工作单与台账
  python3 scripts/review_workbench.py --apply concl.json  # 回填结论（concl.json: {case_id: {结论, 意见}}）
"""
from __future__ import annotations

import argparse
import json
import random
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "scripts"))
import case_lint as L  # noqa: E402

OUT_DIR = ROOT / "artifacts" / "review"
RESULTS = ROOT / "artifacts" / "automation_results.json"
KNOWN = ROOT / "artifacts" / "known-failures.json"
P1_SAMPLE_RATIO = 0.30
SEED = 20261006

# ---- 域划分（尚品汇：管理端 /admin/** + 商城 /api/**）----
DOMAIN_RULES = [
    ("/admin/auth", "管理端认证"), ("/admin/users", "RBAC"), ("/admin/roles", "RBAC"),
    ("/admin/depts", "RBAC"), ("/admin/permissions", "RBAC"), ("/admin/dicts", "RBAC"),
    ("/admin/dict-items", "RBAC"), ("/admin/dashboard", "工作台"),
    ("/admin/categories", "商品域"), ("/admin/brands", "商品域"), ("/admin/attributes", "商品域"),
    ("/admin/products", "商品域"),
    ("/admin/stocks", "库存域"), ("/admin/warehouses", "库存域"), ("/admin/in-out", "库存域"),
    ("/admin/orders", "交易域后台"), ("/admin/after-sales", "交易域后台"),
    ("/admin/customers", "会员管理"),
    ("/admin/site", "内容配置"), ("/admin/home", "内容配置"), ("/admin/reviews", "内容配置"),
    ("/admin/system-configs", "内容配置"),
    ("/admin/files", "文件"), ("/admin/operation-logs", "操作日志"),
    ("/api/auth", "C端认证"), ("/api/me", "会员中心"), ("/api/addresses", "会员中心"),
    ("/api/favorites", "会员中心"), ("/api/history", "会员中心"),
    ("/api/site", "浏览"), ("/api/home", "浏览"), ("/api/categories", "浏览"),
    ("/api/products", "浏览"), ("/api/search", "浏览"),
    ("/api/cart", "购物车"), ("/api/orders", "订单支付"), ("/api/payment", "订单支付"),
    ("/api/after-sales", "售后评价"), ("/api/reviews", "售后评价"), ("/api/files", "文件"),
]

BATCH_OF = {
    "管理端认证": "批次1 认证与会员", "C端认证": "批次1 认证与会员",
    "会员管理": "批次1 认证与会员", "会员中心": "批次1 认证与会员",
    "商品域": "批次2 商品", "浏览": "批次2 商品",
    "库存域": "批次3 库存",
    "交易域后台": "批次4 交易", "购物车": "批次4 交易", "订单支付": "批次4 交易",
    "RBAC": "批次5 管理", "工作台": "批次5 管理", "内容配置": "批次5 管理",
    "文件": "批次5 管理", "操作日志": "批次5 管理",
    "售后评价": "批次6 售后与业务流", "业务流": "批次6 售后与业务流",
}


def domain_of(row: dict) -> str:
    if row["case_id"].startswith("BUS-"):
        return "业务流"
    path = row["method_url"]
    for key, name in DOMAIN_RULES:
        if key in path:
            return name
    return "其他"


def known_notes() -> dict[str, str]:
    """已知缺陷/观察项登记表 → 评审人预标注（人据此跳过缺陷导致的红，但评估用例本身是否合理）。"""
    if not KNOWN.exists():
        return {}
    reg = json.loads(KNOWN.read_text(encoding="utf-8")).get("registered", {})
    return {cid: f"{v.get('defect', '')} {v.get('reason', '')}".strip() for cid, v in reg.items()}


def last_outcomes() -> dict[str, str]:
    """最近执行结果。多 nodeid 覆盖防护：同一用例任一失败即判失败（不取最后写入为准）。"""
    if not RESULTS.exists():
        return {}
    label = {"passed": "通过", "failed": "失败", "skipped": "跳过", "error": "错误"}
    mapping: dict[str, str] = {}
    for r in json.loads(RESULTS.read_text(encoding="utf-8")).get("results", []):
        outcome = label.get(r.get("outcome", ""), r.get("outcome", ""))
        for cid in r.get("case_ids", []):
            if mapping.get(cid) not in ("失败", "错误"):
                mapping[cid] = outcome
    return mapping


def strip_html(text: str) -> str:
    return re.sub(r"<br\s*/?>", "\n", text).strip()


def expectation_digest(assertions: str, http: str, code: str, limit: int = 56) -> str:
    parts = [p.strip() for p in assertions.split("；") if p.strip()]
    head = f"HTTP={http.split('（')[0].strip()} / code={code.split('（')[0].strip()}"
    core = next((p for p in parts if not p.startswith(("HTTP", "code=", "error.code"))),
                parts[-1] if parts else "")
    core = re.sub(r"^断言", "", core).strip("。；")
    digest = f"{head}；{core}" if core else head
    return digest[:limit] + ("…" if len(digest) > limit else "")


class XlsxBuilder:
    def __init__(self):
        import openpyxl  # noqa: F401
        self.wb = openpyxl.Workbook()
        self.openpyxl = openpyxl

    def sheet(self, name: str, headers: list[str], widths: list[int]):
        ws = self.wb.create_sheet(name)
        ws.append(headers)
        bold = self.openpyxl.styles.Font(bold=True)
        fill = self.openpyxl.styles.PatternFill("solid", fgColor="DDEBF7")
        for i, (h, w) in enumerate(zip(headers, widths), 1):
            c = ws.cell(row=1, column=i)
            c.font = bold
            c.fill = fill
            ws.column_dimensions[c.column_letter].width = w
        ws.freeze_panes = "D2"
        return ws

    def save(self, path: Path):
        if self.wb.sheetnames and self.wb.sheetnames[0] == "Sheet":
            del self.wb["Sheet"]
        path.parent.mkdir(parents=True, exist_ok=True)
        self.wb.save(path)


def build() -> int:
    rows = L.parse_inventory(L.DEFAULT_INV)
    notes = known_notes()
    outcomes = last_outcomes()
    p0 = [r for r in rows if r["priority"] == "P0"]
    p1 = [r for r in rows if r["priority"] == "P1"]

    random.seed(SEED)
    by_domain: dict[str, list[dict]] = defaultdict(list)
    for r in p1:
        by_domain[domain_of(r)].append(r)
    p1_sample: list[dict] = []
    for dom, items in sorted(by_domain.items()):
        k = max(1, round(len(items) * P1_SAMPLE_RATIO))
        p1_sample += random.sample(items, min(k, len(items)))
    p1_sample.sort(key=lambda r: (domain_of(r), r["case_id"]))

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    b = XlsxBuilder()
    headers = ["批次", "范围", "用例ID", "标题", "优先级", "预期摘要", "最近执行", "缺陷/观察项", "结论", "评审意见"]
    widths = [18, 8, 30, 30, 6, 60, 9, 30, 11, 40]
    all_review = sorted(p0, key=lambda x: (BATCH_OF.get(domain_of(x), "批次6 售后与业务流"), x["case_id"])) + \
        sorted(p1_sample, key=lambda x: (BATCH_OF.get(domain_of(x), "批次6 售后与业务流"), x["case_id"]))
    ws = b.sheet("评审总表", headers, widths)
    from openpyxl.worksheet.datavalidation import DataValidation
    for r in all_review:
        scope = "P0全评" if r["priority"] == "P0" else "P1抽评"
        ws.append([
            BATCH_OF.get(domain_of(r), "批次6 售后与业务流"), scope, r["case_id"], r["title"], r["priority"],
            expectation_digest(r["assertions"], r["expected_http"], r["expected_code"]),
            outcomes.get(r["case_id"], "—"), notes.get(r["case_id"], ""), "", "",
        ])
        for col in (6, 8, 10):
            ws.cell(row=ws.max_row, column=col).alignment = b.openpyxl.styles.Alignment(wrap_text=True, vertical="top")
    dv = DataValidation(type="list", formula1='"通过,需修改,拒绝,缺陷跟踪"', allow_blank=True)
    ws.add_data_validation(dv)
    dv.add(f"I2:I{ws.max_row}")

    ws_d = b.sheet("用例详情",
                   ["用例ID", "标题", "前置条件", "请求（方法+URL）", "请求头", "参数",
                    "预期HTTP", "预期业务码", "完整断言", "清理", "备注"],
                   [30, 30, 40, 24, 24, 40, 8, 16, 56, 22, 30])
    for r in sorted(rows, key=lambda x: x["case_id"]):
        ws_d.append([r["case_id"], r["title"], strip_html(r["precondition"]), r["method_url"], r["headers"],
                     strip_html(r["payload"]), r["expected_http"], r["expected_code"],
                     r["assertions"], r["cleanup"], r["notes"]])
        for col in (3, 6, 9, 11):
            ws_d.cell(row=ws_d.max_row, column=col).alignment = b.openpyxl.styles.Alignment(wrap_text=True, vertical="top")

    ws_i = b.sheet("说明", ["评审怎么用（30 秒看懂）"], [150])
    for line in [
        "1. 只需在「评审总表」填两列：结论（下拉：通过/需修改/拒绝/缺陷跟踪）+ 评审意见；其余全部预填。",
        f"2. 评审总表 = P0 全评 {len(p0)} 条 + P1 抽评 {len(p1_sample)} 条；按「批次」列分批过。",
        "3. 「最近执行」「缺陷/观察项」是执行侧事实：缺陷导致的红不算用例问题，但请评估用例本身是否仍合理。",
        "4. 需要看原文（前置/参数/完整断言）时，去「用例详情」页按用例ID 查。",
        "5. 意见写法：检查单序号+问题+建议，例：4：预期写 400 但接口文档是 404，建议改预期并补回查。",
        "6. 结论列会被发布门禁 C6 直接读取：p0_reviewed / p0_total 必须 100% 才放行。",
    ]:
        ws_i.append([line])
        ws_i.cell(row=ws_i.max_row, column=1).alignment = b.openpyxl.styles.Alignment(wrap_text=True, vertical="top")

    target = OUT_DIR / "评审工作单.xlsx"
    try:
        b.save(target)
    except PermissionError:
        target = OUT_DIR / "评审工作单-精简版.xlsx"
        b.save(target)
    print(f"[工作单] {target}")

    # ---- C6 机器台账：只带事实 + 空结论，禁止本脚本代填结论 ----
    items = [{
        "case_id": r["case_id"], "priority": r["priority"], "domain": domain_of(r),
        "batch": BATCH_OF.get(domain_of(r), "批次6 售后与业务流"), "title": r["title"],
        "method_url": r["method_url"],
        "最近执行": outcomes.get(r["case_id"], "未执行"),
        "已知缺陷": notes.get(r["case_id"], ""),
        "预期摘要": expectation_digest(r["assertions"], r["expected_http"], r["expected_code"], 200),
        "结论": "", "意见": "",
    } for r in p0]
    ledger = {"p0_total": len(items), "p0_reviewed": 0,
              "method": "未回填（结论列须由评审人填写）", "items": items}
    (OUT_DIR / "p0-review.json").write_text(json.dumps(ledger, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"[台账] {OUT_DIR / 'p0-review.json'}  P0 {len(items)} 条，已评审 0")

    guide = f"""# 评审指引（尚品汇版）

> 配套：`评审工作单.xlsx`（"评审总表" = P0 全评 {len(p0)} 条 + P1 抽评 {len(p1_sample)} 条；"用例详情"页 = 原文备查）
> 台账：`p0-review.json`（C6 输入，结论列由评审人回填，脚本不代填）

## 一、门禁口径
- 存量豁免：已在跑的自动化不因"未评审"挂起；
- 单独撤回：评审发现 Blocker（预期不可断言 / 步骤不可执行 / 数据依赖缺失 / 业务预期错误）→ 该条 xfail，修正复评后恢复；
- 增量生效：新增/修改用例必须 lint 清零 + 评审通过才准上传执行。

## 二、谁审什么
lint 已机械校验（人不用重复看）：三段式标题/禁用词/唯一性/预期格式/优先级/ID 唯一/去重/需求引用存在性/断言三要素形态。
人只看 6 件事：① 可执行性；② 语义正确性（对照接口文档与「已知缺陷」列）；③ 数据依赖有明确准备方式；
④ 业务预期（资金/库存/权限/状态机）；⑤ 追溯（需求编号）；⑥ 覆盖深度（数据层/副作用）。

## 三、批次
| 批次 | 范围 | P0 条数 |
|------|------|---------|
""" + "\n".join(
        f"| {b_} | {b_.split(' ', 1)[1]} | {sum(1 for r in p0 if BATCH_OF.get(domain_of(r)) == b_)} |"
        for b_ in dict.fromkeys(BATCH_OF.values())
    ) + f"""

## 四、怎么填
1. 打开工作单 →「评审总表」逐条过，只填「结论」「评审意见」两列；
2. 结论四选一：通过 / 需修改 / 拒绝（Blocker，需重写）/ 缺陷跟踪（红线来自已登记缺陷，用例本身合理）；
3. 意见写法：`检查单序号 + 问题 + 建议`；
4. 回填命令：`python3 scripts/review_workbench.py --apply <conclusions.json>`（JSON 形如 `{{"用例ID": {{"结论": "通过", "意见": ""}}}}`）。

## 五、禅道留痕
本部署禅道无可用原生评审入口（已实测）。等效留痕：① 结论回写用例备注（随同步进禅道，标记"经评审"）；
② `p0-review.json` + 本指引作为审计记录。
"""
    (OUT_DIR / "评审指引.md").write_text(guide, encoding="utf-8")
    print(f"[指引] {OUT_DIR / '评审指引.md'}")
    print(f"P0 全评 {len(p0)} 条 | P1 抽评 {len(p1_sample)} 条（{P1_SAMPLE_RATIO:.0%}）")
    print("批次分布:", dict(Counter(BATCH_OF.get(domain_of(r), "批次6 售后与业务流") for r in p0)))
    print("已标注缺陷/观察项:", sum(1 for r in p0 if r["case_id"] in notes))
    return 0


def apply(path: Path) -> int:
    ledger_path = OUT_DIR / "p0-review.json"
    ledger = json.loads(ledger_path.read_text(encoding="utf-8"))
    payload = json.loads(path.read_text(encoding="utf-8"))
    meta = payload.pop("_meta", {})
    for item in ledger["items"]:
        got = payload.get(item["case_id"])
        if got:
            item["结论"] = got.get("结论", "")
            item["意见"] = got.get("意见", "")
    reviewed = sum(1 for x in ledger["items"] if x["结论"])
    ledger["p0_reviewed"] = reviewed
    ledger["method"] = meta.get("method", "评审人回填")
    ledger_path.write_text(json.dumps(ledger, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"P0 评审台账：{reviewed}/{ledger['p0_total']}（{reviewed / max(1, ledger['p0_total']):.0%}）")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", type=Path)
    args = ap.parse_args()
    return apply(args.apply) if args.apply else build()


if __name__ == "__main__":
    sys.exit(main())
