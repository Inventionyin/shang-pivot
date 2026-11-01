#!/usr/bin/env python3
"""Read-only preflight for ZenTao title and formal-testability cleanup."""
from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.zentao_sync import ZenTaoClient, parse_inventory  # noqa: E402


FIELDS = ("title", "precondition", "method_url", "headers", "payload", "steps", "expected_http", "expected_code", "assertions", "cleanup", "notes")
IP_RE = re.compile(r"(?:111\.170\.33\.2|103\.236\.91\.136)(?::\d+)?")
EMPTY_VALUE_RE = re.compile(r"(?:\"[^\"]+\"\s*:\s*\"\"|<(?:unique|test|id|remark|runtime secret)>|字段待确认|object（字段待确认）|JSON schema: object)", re.I)
HARDCODED_ID_RE = re.compile(r"(?:^|[=:\\\"'])\s*(?:addressId|productId|orderId|orderItemId|favId|id)\s*[=:：]\s*1(?:\D|$)", re.I)
PREP_WORDS = re.compile(r"准备|先|登录|获取|查询|创建|新增|加购|动态|fixture|预置|token|取默认|清理", re.I)
DEPENDENCY_WORDS = re.compile(r"已登录|token|已有|有 |准备|存在|账号未注册|本人有|ADMIN|SUBMITTED|VALID|USED|EXPIRED|待支付|已支付|已完成|商品", re.I)


def strip_prefix(title: str) -> str:
    body = re.sub(r"^\s*\[API-AUTO\]\[[^\]]+\]\s*", "", title or "")
    return body.strip()


def source_for(case: dict[str, str]) -> str:
    notes = case.get("notes", "").strip()
    if notes:
        return notes
    method = case.get("method_url", "").split(" ", 1)[-1].split("?", 1)[0]
    segment = method.strip("/").split("/")[1] if "/" in method else "通用约定"
    return f"接口文档.md § {segment}；back/src/main/java/com/web/controller/ 与 service/ 对应实现"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", type=Path, default=ROOT / "test_cases_inventory.md")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "artifacts" / "case-preflight")
    args = parser.parse_args()

    inventory = parse_inventory(args.inventory)
    client = ZenTaoClient(
        base_url=os.environ.get("ZENTAO_BASE_URL", "http://YOUR_SERVER_IP:60082"),
        username=os.environ["ZENTAO_USER"],
        password=os.environ["ZENTAO_PASSWORD"],
        product_id=int(os.environ.get("ZENTAO_PRODUCT_ID", "1")),
    )
    records = client.case_records()
    args.output_dir.mkdir(parents=True, exist_ok=True)

    inventory_by_id = {case["case_id"]: case for case in inventory}
    title_rows = []
    title_ok = 0
    missing_remote = []
    for stable_id in sorted(inventory_by_id):
        case = inventory_by_id[stable_id]
        record = records.get(stable_id)
        if not record:
            missing_remote.append(stable_id)
            continue
        old = str(record.get("title", ""))
        new = f"[API-AUTO][{stable_id}] {strip_prefix(old)}"
        if old == new:
            title_ok += 1
        title_rows.append((stable_id, str(record.get("id", "")), old, new, "保持" if old == new else "需修改"))

    title_report = [
        "# 标题规范化差异表（只读扫描）", "",
        "目标格式：`[API-AUTO][稳定ID] 原标题正文`。匹配依据仅为禅道 keyword `API-AUTO,<稳定ID>`。", "",
        f"- 本地清单：{len(inventory)} 条；禅道按 keyword 找到：{len(title_rows)} 条；缺失：{len(missing_remote)} 条；当前已符合：{title_ok}/{len(inventory)} 条", "",
        "| 稳定ID | 禅道记录标识 | 旧标题 | 拟改新标题 | 状态 |", "|---|---|---|---|---|",
    ]
    for stable_id, record_id, old, new, status in title_rows:
        title_report.append("| " + " | ".join(x.replace("|", "\\|").replace("\n", " ") for x in (stable_id, record_id, old, new, status)) + " |")
    if missing_remote:
        title_report += ["", "## keyword 未找到", "", *[f"- `{item}`" for item in missing_remote]]
    (args.output_dir / "title_normalization_diff.md").write_text("\n".join(title_report) + "\n", encoding="utf-8")

    wait_rows = []
    quality_rows = []
    for stable_id, case in inventory_by_id.items():
        wait_fields = [field for field in FIELDS if "待确认" in case.get(field, "")]
        all_text = " ".join(case.get(field, "") for field in FIELDS)
        hardcoded = sorted(set(IP_RE.findall(all_text)))
        placeholders = sorted(set(EMPTY_VALUE_RE.findall(all_text)) | set(HARDCODED_ID_RE.findall(all_text)))
        precondition = case.get("precondition", "")
        steps = case.get("steps", "")
        needs_prep = bool(DEPENDENCY_WORDS.search(precondition)) and not bool(PREP_WORDS.search(steps))
        cleanup = case.get("cleanup", "").strip()
        missing_cleanup = not cleanup or cleanup in {"无", "N/A", "-"} or "待确认" in cleanup
        if wait_fields:
            wait_rows.append((stable_id, ", ".join(wait_fields), case.get("title", ""), source_for(case)))
        if hardcoded or placeholders or needs_prep or missing_cleanup:
            reasons = []
            if hardcoded:
                reasons.append("硬编码环境/IP")
            if placeholders:
                reasons.append("占位符或固定 ID")
            if needs_prep:
                reasons.append("前置数据缺准备步骤")
            if missing_cleanup:
                reasons.append("清理为空/无动作/待确认")
            quality_rows.append((stable_id, "; ".join(reasons), case.get("precondition", ""), case.get("payload", ""), case.get("cleanup", "")))

    wait_report = [
        "# 待确认清单（只读扫描）", "",
        f"共 {len(wait_rows)} 条用例包含“待确认”；其中标题包含“待确认”的有 {sum('title' in row[1].split(', ') for row in wait_rows)} 条。", "",
        "| 稳定ID | 待确认字段 | 当前标题 | 拟依据 |", "|---|---|---|---|",
    ]
    for row in sorted(wait_rows):
        wait_report.append("| " + " | ".join(x.replace("|", "\\|").replace("\n", " ") for x in row) + " |")
    (args.output_dir / "pending-confirmation.md").write_text("\n".join(wait_report) + "\n", encoding="utf-8")

    quality_report = [
        "# 硬编码、占位符、数据准备与清理缺口（只读扫描）", "",
        f"- 符合标题格式：{title_ok}/{len(inventory)}（目标 232/232，需用户确认后才会修改）",
        f"- 含待确认：{len(wait_rows)}；标题含待确认：{sum('title' in row[1].split(', ') for row in wait_rows)}",
        f"- 存在环境/IP硬编码：{sum('硬编码环境/IP' in row[1] for row in quality_rows)}",
        f"- 存在占位符/固定ID：{sum('占位符或固定 ID' in row[1] for row in quality_rows)}",
        f"- 前置数据缺准备步骤：{sum('前置数据缺准备步骤' in row[1] for row in quality_rows)}",
        f"- 清理为空/无动作/待确认：{sum('清理为空/无动作/待确认' in row[1] for row in quality_rows)}", "",
        "| 稳定ID | 问题类别 | 当前前置条件 | 当前参数 | 当前清理 |", "|---|---|---|---|---|",
    ]
    for row in sorted(quality_rows):
        quality_report.append("| " + " | ".join(x.replace("|", "\\|").replace("\n", " ") for x in row) + " |")
    (args.output_dir / "data-quality-gaps.md").write_text("\n".join(quality_report) + "\n", encoding="utf-8")

    print(f"inventory={len(inventory)} remote_keyword_matches={len(title_rows)} title_compliant={title_ok} pending={len(wait_rows)} quality_rows={len(quality_rows)}")
    print(f"reports={args.output_dir}")


if __name__ == "__main__":
    main()
