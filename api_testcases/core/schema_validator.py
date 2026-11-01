#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""响应契约校验器：把 OpenAPI 生成的 schema 变成可执行的断言（四层断言的数据层与可观测层）。

为什么单独一层：
  `assert_engine` 负责**业务语义**断言（回查、状态变化、业务码、字段取值），
  本模块负责**契约结构**断言（字段类型/结构、Long 序列化约定、traceId 可观测性）。
  两者互补：业务对了但字段类型悄悄变了，只有这一层能抓到。

四层断言（对齐 docs/测试资料/rule.md §断言分层）：
  L1 协议   HTTP 状态码（由调用方断言）+ 信封 code/msg 类型
  L2 业务   业务码语义（assert_engine 负责）
  L3 数据   JSON Schema 结构校验 + Long 必须序列化为字符串
  L4 可观测 traceId 存在、格式正确、与响应头 X-Trace-Id 一致

用法：
    from core.schema_validator import SchemaValidator
    v = SchemaValidator()
    problems = v.check("GET", "/admin/brands", status, body, headers)
    # problems: [{"layer": "L3", "message": "..."}]
"""
from __future__ import annotations

import json
import re
from pathlib import Path

import jsonschema
from jsonschema import Draft202012Validator

SCHEMA_DIR = Path(__file__).resolve().parents[1] / "schemas"
TRACE_RE = re.compile(r"^[0-9a-f]{32}$")
TRACE_HEADER = "X-Trace-Id"

L1, L3, L4 = "L1协议", "L3数据", "L4可观测"


def _deref_name(node) -> bool:
    """判断 data 是否是「空体」（ResultVoid：无 type 也无 properties），空体不要求 data 存在。"""
    if not isinstance(node, dict):
        return True
    return not node.get("type") and not node.get("properties") and not node.get("items")


class SchemaValidator:
    def __init__(self, schema_dir: Path | None = None, enabled: bool = True):
        self.enabled = enabled
        d = schema_dir or SCHEMA_DIR
        self.endpoints: dict = {}
        self.meta: dict = {}
        ep_file, meta_file = d / "endpoints.json", d / "meta.json"
        if ep_file.exists():
            try:
                self.endpoints = json.loads(ep_file.read_text(encoding="utf-8")).get("endpoints", {})
            except json.JSONDecodeError:
                self.endpoints = {}
        if meta_file.exists():
            try:
                self.meta = json.loads(meta_file.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                self.meta = {}
        self.long_fields = set(self.meta.get("long_fields") or [])
        self._validators: dict[str, Draft202012Validator] = {}

    # ------------------------------------------------------------------ #
    @property
    def available(self) -> bool:
        return self.enabled and bool(self.endpoints)

    def _validator(self, key: str):
        if key not in self._validators:
            schema = self.endpoints[key].get("response") or {}
            self._validators[key] = Draft202012Validator(schema)
        return self._validators[key]

    def check(self, method: str, path: str, status: int, body, headers: dict | None = None) -> list[dict]:
        """返回违规列表；空列表 = 通过。非 JSON / 非 2xx 直接跳过（不误报）。"""
        if not self.available:
            return []
        if not isinstance(status, int) or not (200 <= status < 300):
            return []
        if not isinstance(body, dict):
            return []
        # 导出 CSV / 文件流等非 JSON 响应：驱动用 {"_raw": ...} 表示，没有信封也没有 traceId，
        # 不属于契约校验范围，直接跳过（否则 GET /admin/orders/export 等会误报「缺少 traceId」）。
        if "_raw" in body:
            return []

        problems: list[dict] = []
        headers = {k.lower(): v for k, v in (headers or {}).items()}

        # ---- L1 信封 ----
        if "code" in body and not isinstance(body["code"], int):
            problems.append({"layer": L1, "message": f"信封 code 应为整数，实际 {type(body['code']).__name__}"})
        if "msg" in body and not isinstance(body["msg"], str):
            problems.append({"layer": L1, "message": f"信封 msg 应为字符串，实际 {type(body['msg']).__name__}"})

        key = f"{method.upper()} {path}"
        ep = self.endpoints.get(key)
        if ep is None:
            return problems + self._obs_check(body, headers)

        # 成功响应且非空体 → data 必须存在（Jackson non_null 会省略 null，但成功必有数据）
        if body.get("code") == 0:
            data_node = (ep.get("response") or {}).get("properties", {}).get("data")
            if data_node is not None and not _deref_name(data_node) and "data" not in body:
                problems.append({"layer": L1, "message": "code=0 但响应缺少 data 字段"})

        # ---- L3 结构（OpenAPI schema） ----
        if ep.get("has_schema"):
            try:
                v = self._validator(key)
                for err in sorted(v.iter_errors(body), key=lambda e: list(e.absolute_path))[:8]:
                    loc = ".".join(str(p) for p in err.absolute_path) or "(root)"
                    problems.append({"layer": L3, "message": f"schema 不符 {loc}：{err.message[:160]}"})
            except jsonschema.exceptions.SchemaError as exc:
                problems.append({"layer": L3, "message": f"生成的 schema 自身非法：{str(exc)[:160]}"})

        # ---- L3 Long 序列化约定（严格：必须是字符串） ----
        problems += self._long_check(body)

        # ---- L4 可观测 ----
        problems += self._obs_check(body, headers)
        return problems

    # ------------------------------------------------------------------ #
    def _long_check(self, body) -> list[dict]:
        """Long 字段必须序列化为字符串：全局 Jackson ToStringSerializer 的既定行为。

        取到数字即为回归（前端会丢精度，且与 docs/06-baseline/04-字段映射.md 的约定冲突）。
        """
        if not self.long_fields:
            return []
        out: list[dict] = []

        def walk(node, path=""):
            if isinstance(node, dict):
                for k, v in node.items():
                    here = f"{path}.{k}" if path else k
                    if k in self.long_fields and v is not None and not isinstance(v, str):
                        out.append({"layer": L3,
                                    "message": f"Long 字段 {here} 应为字符串，实际 {type(v).__name__}={v!r}"})
                    walk(v, here)
            elif isinstance(node, list):
                for i, v in enumerate(node[:20]):      # 大列表抽样，避免刷屏
                    walk(v, f"{path}[{i}]")

        walk(body)
        return out[:10]

    def _obs_check(self, body, headers: dict) -> list[dict]:
        out: list[dict] = []
        trace = body.get("traceId")
        if trace is None:
            out.append({"layer": L4, "message": "响应缺少 traceId（可观测性契约）"})
        elif not isinstance(trace, str):
            out.append({"layer": L4, "message": f"traceId 应为字符串，实际 {type(trace).__name__}"})
        elif not TRACE_RE.match(trace):
            out.append({"layer": L4, "message": f"traceId 格式异常（应为 32 位十六进制）：{trace!r}"})

        hdr = headers.get(TRACE_HEADER.lower())
        if hdr and trace and hdr != trace:
            out.append({"layer": L4, "message": f"响应头 {TRACE_HEADER}={hdr} 与响应体 traceId={trace} 不一致"})
        return out


def summarize(problems: list[dict]) -> dict:
    """按层汇总，便于写进 Allure 与终端摘要。"""
    out: dict[str, int] = {}
    for p in problems:
        out[p["layer"]] = out.get(p["layer"], 0) + 1
    return out
