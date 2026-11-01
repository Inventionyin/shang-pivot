#!/usr/bin/env python3
"""报告数据契约检查：并行发布前后，接口视图数据结构必须完全一致。

比较两份 api-matrix.json 的「键集合 + 类型」，不比较值（用例数量与时序允许变化）。
用法：python3 scripts/check_report_contract.py --baseline old.json --current new.json
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

_TYPE_MAP = {
    dict: "object", list: "array", str: "string", int: "number",
    float: "number", bool: "boolean", type(None): "null",
}


def schema_of(value, depth: int = 0):
    """抽取结构骨架：object → 键→子结构；array → 元素结构；标量 → 类型名。"""
    if depth > 6:
        return "truncated"
    if isinstance(value, dict):
        return {key: schema_of(item, depth + 1) for key, item in sorted(value.items())}
    if isinstance(value, list):
        return ["array-of", schema_of(value[0], depth + 1)] if value else ["array"]
    return _TYPE_MAP.get(type(value), type(value).__name__)


def diff(baseline, current, path: str = "$") -> list[str]:
    problems: list[str] = []
    if type(baseline) is not type(current):
        return [f"{path}: 类型变化 {type(baseline).__name__} -> {type(current).__name__}"]
    if isinstance(baseline, dict):
        missing = set(baseline) - set(current)
        added = set(current) - set(baseline)
        for key in sorted(missing):
            problems.append(f"{path}.{key}: 字段缺失")
        for key in sorted(added):
            problems.append(f"{path}.{key}: 新增字段")
        for key in sorted(set(baseline) & set(current)):
            problems.extend(diff(baseline[key], current[key], f"{path}.{key}"))
    elif isinstance(baseline, list) and baseline and current:
        problems.extend(diff(baseline[0], current[0], f"{path}[0]"))
    return problems


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", required=True)
    parser.add_argument("--current", required=True)
    args = parser.parse_args()
    baseline = schema_of(json.loads(Path(args.baseline).read_text(encoding="utf-8")))
    current = schema_of(json.loads(Path(args.current).read_text(encoding="utf-8")))
    problems = diff(baseline, current)
    if problems:
        print(f"[FAIL] 契约变化 {len(problems)} 处：")
        for item in problems[:30]:
            print("  -", item)
        raise SystemExit(1)
    print("[OK] 报告数据结构与基线一致（字段集合/类型未变化）")


if __name__ == "__main__":
    main()
