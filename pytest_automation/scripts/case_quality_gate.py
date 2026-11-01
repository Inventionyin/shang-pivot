#!/usr/bin/env python3
"""用例质量门禁：结构/规范/归属/方法/DB 断言/评审的自动检查。

用法：
  python3 scripts/case_quality_gate.py            # 输出报告，错误则 exit 1
  python3 scripts/case_quality_gate.py --strict-db  # 写接口必须已有 DB 落库断言
  python3 scripts/case_quality_gate.py --json artifacts/quality-gate/report.json
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPO = ROOT.parent
CASES = ROOT / "test_cases.json"
META = ROOT / "data" / "case_meta.yaml"
REGISTRY = ROOT / "data" / "case_registry.json"
MAPPING = ROOT / "artifacts" / "case_endpoint_map.json"
MODEL = REPO / "api_testcases" / "artifacts" / "endpoint-model.json"
WRITE_CONTRACTS = ROOT / "artifacts" / "write-contracts.json"
OUT = ROOT / "artifacts" / "quality-gate"


def load(path: Path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001
        return default


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--strict-db", action="store_true")
    parser.add_argument("--json", default=str(OUT / "report.json"))
    args = parser.parse_args()

    errors: list[str] = []
    warnings: list[str] = []

    cases = load(CASES, {}).get("cases") or []
    registry = (load(REGISTRY, {}) or {}).get("cases") or {}
    mapping = load(MAPPING, {}) or {}
    model = load(MODEL, {}).get("endpoints") or []
    model_keys = {(item["method"], item["path"]) for item in model}
    allow_node: set[str] = set()
    allow_file = ROOT / "data" / "quality_gate_allowlist.yaml"
    if allow_file.exists():
        try:
            import yaml

            allow_data = yaml.safe_load(allow_file.read_text(encoding="utf-8")) or {}
            allow_node = {item.get("node", "") for item in (allow_data.get("unregistered_endpoints") or [])}
        except Exception as exc:  # noqa: BLE001
            warnings.append(f"质量门禁豁免表解析失败：{exc}")

    # 1) 注册表与稳定编号
    missing_registry = [c["case_id"] for c in cases if c.get("script") not in registry]
    if missing_registry:
        errors.append(f"注册表缺少 {len(missing_registry)} 条用例（先跑 export_cases.py）：{missing_registry[:5]}")

    # 2) 元数据（标题/验证点）
    try:
        import yaml

        meta = yaml.safe_load(META.read_text(encoding="utf-8")) or {}
    except Exception as exc:  # noqa: BLE001
        errors.append(f"case_meta.yaml 无法解析：{exc}")
        meta = {}
    meta_cases = meta.get("cases") or {}
    for case in cases:
        key = case.get("file", "") + "::" + case.get("script", "").split("::")[-1].split("[")[0]
        entry = meta_cases.get(key) or {}
        if not entry.get("title"):
            errors.append(f"{case['case_id']} 缺少 case_meta.title（{key}）")
        if not str(entry.get("verify") or "").strip():
            errors.append(f"{case['case_id']} 缺少 case_meta.verify（{key}）")

    # 3) 请求方式与接口归属
    for nodeid, endpoints in (mapping.get("tests") or {}).items():
        if nodeid.startswith("tests/ui/") or "test_ui_purchase_flow" in nodeid:
            continue
        if nodeid in allow_node:
            continue
        for endpoint in endpoints:
            method, _, path = endpoint.partition(" ")
            if (method, path) not in model_keys:
                errors.append(f"{nodeid} 调用了未登记端点 {endpoint}（端点模型不一致）")

    # 4) 写接口 DB 断言覆盖（首次运行无契约时给警告，strict 才阻断）
    write_endpoints = {(i["method"], i["path"]) for i in model if i["method"] != "GET"}
    if WRITE_CONTRACTS.exists():
        contracts = load(WRITE_CONTRACTS, {}).get("endpoints") or {}
        covered = {k for k, v in contracts.items() if v.get("tests")}
        missing = sorted(f"{m} {p}" for m, p in write_endpoints if f"{m} {p}" not in covered)
        if missing:
            message = f"{len(missing)} 个写接口缺少 DB 落库断言（示例：{missing[:5]}）"
            (errors if args.strict_db else warnings).append(message)
    else:
        (errors if args.strict_db else warnings).append("未生成 write-contracts.json，写接口 DB 断言覆盖未知")

    # 5) 硬编码业务主键（正向 ID）扫描
    hardcoded: list[str] = []
    for file in (ROOT / "tests").rglob("*.py"):
        text = file.read_text(encoding="utf-8", errors="replace")
        for match in re.finditer(r'"(\d{5})"', text):
            value = match.group(1)
            if value.startswith(("100", "101", "102")):
                line = text[: match.start()].count("\n") + 1
                hardcoded.append(f"{file.relative_to(ROOT)}:{line} -> {value}")
    if hardcoded:
        warnings.append(f"发现 {len(hardcoded)} 处疑似硬编码业务主键（需人工确认）：{hardcoded[:5]}")

    # 6) 评审门禁（新增/修改必须 approved；P0 债务只告警）
    sys.path.insert(0, str(ROOT / "scripts"))
    import review_cases as review

    review_problems = review.check(
        review.load_cases(), review.load_ledger(), allow_new=False, strict_p0=False
    )
    errors.extend(f"评审：{item}" for item in review_problems)

    payload = {
        "cases": len(cases),
        "registry_entries": len(registry),
        "errors": errors,
        "warnings": warnings,
    }
    OUT.mkdir(parents=True, exist_ok=True)
    Path(args.json).write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    (OUT / "report.md").write_text(
        "# 用例质量门禁报告\n\n"
        f"- 用例数：{len(cases)}；注册表条目：{len(registry)}\n"
        f"- 错误：{len(errors)}；警告：{len(warnings)}\n\n"
        "## 错误\n\n" + ("\n".join(f"- {e}" for e in errors) or "无") +
        "\n\n## 警告\n\n" + ("\n".join(f"- {w}" for w in warnings) or "无") + "\n",
        encoding="utf-8",
    )
    if errors:
        print(f"[FAIL] 质量门禁 {len(errors)} 个错误：")
        for item in errors[:20]:
            print("  -", item)
        sys.exit(1)
    print(f"[OK] 质量门禁通过（警告 {len(warnings)} 条）")


if __name__ == "__main__":
    main()
