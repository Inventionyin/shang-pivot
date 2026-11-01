#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 OpenAPI 生成接口断言用的 JSON Schema（含信封与业务体），并导出跨接口的「Long 应为字符串」规则。

为什么从 OpenAPI 取：springdoc 已经把 **131/135** 个操作的 200 响应体（含 `Result<T>` 信封、
`PageResult<T>` 分页包装）描述成 OpenAPI 3.1 的 JSON Schema。3.1 的 schema 就是标准 JSON Schema，
可以直接交给 `jsonschema` 校验——不需要自己再写一套模型定义（避免第二套真相）。

生成物（机制区，禁止手改）：
  schemas/endpoints.json  —— "METHOD /path" → {response, request, has_schema}
  schemas/meta.json       —— 生成指纹、覆盖统计、Long 字段名集合

`long_fields` 的来源是 `artifacts/field-map.json`：所有 Java 类型为 Long 的 JSON 字段名。
全局序列化约定把这些字段输出成**字符串**（JacksonConfig 的 ToStringSerializer），
所以「取到数字」就是真 bug。这条规则跨接口通用，写进 schema 元数据供校验器复用。

用法：
  python3 scripts/build_schemas.py [--fingerprint <值>]
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
sys.path.insert(0, str(ROOT / "scripts"))
from baseline_common import sticky_generated_at  # noqa: E402

OPENAPI = REPO / "docs/openapi-v3-api-docs.json"
METHODS = ("get", "post", "put", "delete", "patch")
# 分页包装的 total 也是 Long（PageResult.total），不是实体字段，需单独补进来
EXTRA_LONG_FIELDS = {"total"}


def deref(node, root: dict, depth: int = 0, seen: tuple = ()):
    """就地展开 $ref（OpenAPI 3.1 允许 $ref 带兄弟键，需保留兄弟键内容）。"""
    if depth > 12:
        return {"description": "（$ref 嵌套过深，已截断）"}
    if isinstance(node, list):
        return [deref(x, root, depth + 1, seen) for x in node]
    if not isinstance(node, dict):
        return node

    if "$ref" in node:
        ref = node["$ref"]
        name = ref.rsplit("/", 1)[-1]
        if name in seen:                      # 防御自引用，避免无限展开
            return {"description": f"（循环引用 {name}）"}
        target = root.get("components", {}).get("schemas", {}).get(name)
        if target is None:
            return {"description": f"（未找到 {ref}）"}
        merged = dict(deref(target, root, depth + 1, seen + (name,)))
        # $ref 的兄弟键（如 description）覆盖被引用者
        merged.update({k: deref(v, root, depth + 1, seen + (name,))
                       for k, v in node.items() if k != "$ref"})
        return merged

    return {k: deref(v, root, depth + 1, seen) for k, v in node.items()}


def long_fields_from_field_map() -> list[str]:
    fm_path = ROOT / "artifacts/field-map.json"
    if not fm_path.exists():
        return sorted(EXTRA_LONG_FIELDS)
    fm = json.loads(fm_path.read_text(encoding="utf-8"))
    names = set(EXTRA_LONG_FIELDS)
    for ent in fm.get("entities", {}).values():
        for row in ent.get("rows", []):
            if row.get("java_type") in ("Long", "long"):
                names.add(row["json_field"])
    return sorted(names)


def collect_int64_names(node, acc: set) -> set:
    """收集 schema 里所有 format=int64 的属性名——它们就是 Java 的 Long/long。"""
    if isinstance(node, dict):
        props = node.get("properties")
        if isinstance(props, dict):
            for name, sub in props.items():
                if isinstance(sub, dict) and sub.get("format") == "int64":
                    acc.add(name)
                collect_int64_names(sub, acc)      # 必须递归进嵌套对象，否则漏掉 VO 里的 Long
        if "items" in node:
            collect_int64_names(node["items"], acc)
        for k in ("allOf", "anyOf", "oneOf"):
            for sub in node.get(k) or []:
                collect_int64_names(sub, acc)
    return acc


# `data` 是信封字段，类型随端点而变（可能是对象/数组/字符串），不能按 Long 名规则处理
CONTEXTUAL_FIELDS = {"data"}


def normalize_long_types(node, long_names: set):
    """把 Long 字段的类型从「仅 integer」放宽为「string 或 integer」。

    依据：全局 Jackson ToStringSerializer 把 `Long` 输出成**字符串**（实测 id='10'、total='8'、
    expiresIn='900'），而 springdoc 按 Java 类型记成了 `integer/int64` —— **spec 在这里不准确**。
    放宽后 schema 只做结构校验不会误报；「必须是字符串」这条由 long_fields 规则单独严格断言。
    """
    if isinstance(node, dict):
        props = node.get("properties")
        if isinstance(props, dict):
            for name, sub in props.items():
                if not isinstance(sub, dict):
                    continue
                is_ctx_long = (name in CONTEXTUAL_FIELDS and sub.get("format") == "int64")
                if name in long_names or is_ctx_long:
                    types = sub.get("type")
                    if types == "integer" or (isinstance(types, list) and "integer" in types):
                        sub["type"] = ["string", "integer"]
                        sub.pop("format", None)
                        sub["x-serialized-as"] = "string（Long 经 Jackson 转字符串）"
                normalize_long_types(sub, long_names)
        if "items" in node:
            normalize_long_types(node["items"], long_names)
        for k in ("allOf", "anyOf", "oneOf"):
            for sub in node.get(k) or []:
                normalize_long_types(sub, long_names)
    return node


def build(fingerprint: str, generated_at: str | None = None) -> tuple[dict, dict]:
    spec = json.loads(OPENAPI.read_text(encoding="utf-8"))
    endpoints: dict[str, dict] = {}
    no_schema: list[str] = []

    for path, ops in spec.get("paths", {}).items():
        for method, op in ops.items():
            if method not in METHODS:
                continue
            key = f"{method.upper()} {path}"
            resp_content = op.get("responses", {}).get("200", {}).get("content", {}) or {}
            resp_schema = {}
            for ct in ("*/*", "application/json"):
                candidate = (resp_content.get(ct) or {}).get("schema")
                if candidate:
                    resp_schema = deref(candidate, spec)
                    break
            if not resp_schema:
                no_schema.append(key)

            req_content = (op.get("requestBody") or {}).get("content", {}) or {}
            req_schema = {}
            candidate_req = (req_content.get("application/json") or {}).get("schema")
            if candidate_req:
                req_schema = deref(candidate_req, spec)

            endpoints[key] = {
                "method": method.upper(),
                "path": path,
                "summary": op.get("summary") or "",
                "has_schema": bool(resp_schema),
                "response": resp_schema,
                "request": req_schema,
                "path_params": [p["name"] for p in op.get("parameters", []) if p.get("in") == "path"],
                "query_params": [p["name"] for p in op.get("parameters", []) if p.get("in") == "query"],
            }

    # 实体推导的 Long ∪ spec 标注的 int64（含 VO，如 expiresIn）− 语境字段
    int64_names: set = set()
    for e in endpoints.values():
        collect_int64_names(e.get("response") or {}, int64_names)
    long_fields = sorted((set(long_fields_from_field_map()) | int64_names) - CONTEXTUAL_FIELDS)
    for e in endpoints.values():
        if e.get("response"):
            normalize_long_types(e["response"], set(long_fields))

    meta = {
        "generated_at": generated_at or "",
        "generator": "api_testcases/scripts/build_schemas.py",
        "fingerprint": fingerprint,
        "source": str(OPENAPI.relative_to(REPO)),
        "endpoint_count": len(endpoints),
        "with_response_schema": sum(1 for e in endpoints.values() if e["has_schema"]),
        "without_response_schema": sorted(no_schema),
        "with_request_schema": sum(1 for e in endpoints.values() if e["request"]),
        "long_fields": long_fields,
        "long_field_count": len(long_fields),
        "spec_inaccurate_note": (
            "springdoc 按 Java 类型把 Long 记为 integer/int64，但全局 Jackson ToStringSerializer "
            "实际输出字符串；生成器已把这些字段放宽为 [string, integer]，严格性由 long_fields 规则保证。"),
        "int64_names_from_spec": sorted(int64_names),
    }
    return {"endpoints": endpoints}, meta


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", default=str(ROOT / "schemas"))
    ap.add_argument("--fingerprint", default="(unset)")
    ap.add_argument("--generated-at", default=None)
    args = ap.parse_args()

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    meta_path = out_dir / "meta.json"

    prev = None
    if meta_path.exists():
        try:
            prev = json.loads(meta_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            prev = None

    endpoints, meta = build(args.fingerprint)
    probe = dict(meta)
    meta["generated_at"] = sticky_generated_at(prev, probe, args.generated_at)

    (out_dir / "endpoints.json").write_text(
        json.dumps(endpoints, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(json.dumps({
        "端点": meta["endpoint_count"],
        "有响应 schema": meta["with_response_schema"],
        "无响应 schema": len(meta["without_response_schema"]),
        "有请求 schema": meta["with_request_schema"],
        "Long 字段规则数": meta["long_field_count"],
        "生成时间": meta["generated_at"],
        "输出": str(out_dir),
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
