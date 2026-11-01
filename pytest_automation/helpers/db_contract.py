"""写接口落库断言：请求字段 ↔ DB 行逐字段比对。

契约来源：artifacts/write-contracts.json（由 data/write_contracts.yaml 生成）。
用法：
    row = verify_write("POST /api/addresses", payload, response=address_id)
    row = verify_write("PUT /api/addresses/{id}", payload, path={"id": address_id})
    row = verify_write("POST /api/payment/sandbox", {"orderId": oid}, natural={"order_id": oid})
"""
from __future__ import annotations

import json
from decimal import Decimal
from pathlib import Path

from helpers import db

CONTRACTS = Path(__file__).resolve().parents[1] / "artifacts" / "write-contracts.json"
_cache: dict | None = None


def contracts() -> dict:
    global _cache
    if _cache is None:
        try:
            _cache = json.loads(CONTRACTS.read_text(encoding="utf-8")).get("endpoints") or {}
        except Exception:  # noqa: BLE001
            _cache = {}
    return _cache


def _table_columns(table: str) -> set[str]:
    schema, _, name = table.partition(".")
    rows = db.query(
        "SELECT column_name FROM information_schema.columns WHERE table_schema=%s AND table_name=%s",
        (schema, name),
    )
    return {r["column_name"] for r in rows}


def _locate(contract: dict, response, path, natural) -> dict:
    table = contract["table"]
    locate = contract.get("locate") or {}
    kind = locate.get("type")
    columns = _table_columns(table)
    include_deleted = bool(contract.get("expect_deleted"))
    where_deleted = " AND deleted = FALSE" if ("deleted" in columns and not include_deleted) else ""
    if kind == "response_id":
        row = db.query_one(
            f"SELECT * FROM {table} WHERE id = %s{where_deleted}", (str(response),)
        )
    elif kind == "response_field":
        value = (response or {}).get(locate["key"])
        assert value, f"响应缺少 {locate['key']}，无法定位落库行"
        row = db.query_one(f"SELECT * FROM {table} WHERE id = %s{where_deleted}", (str(value),))
    elif kind == "path_param":
        value = (path or {}).get(locate["key"])
        assert value, f"路径参数缺少 {locate['key']}"
        row = db.query_one(f"SELECT * FROM {table} WHERE id = %s{where_deleted}", (str(value),))
    elif kind == "natural":
        value = (natural or {}).get(locate["column"]) or (natural or {}).get("value")
        assert value, f"缺少定位值 {locate['column']}"
        row = db.query_one(
            f"SELECT * FROM {table} WHERE {locate['column']} = %s{where_deleted} ORDER BY id DESC LIMIT 1",
            (str(value),),
        )
    else:
        raise AssertionError(f"未知定位方式：{kind}")
    assert row, f"{table} 中找不到刚落库的行（locate={kind}）"
    return row


def _convert(value, kind: str):
    if kind == "int":
        return int(value)
    if kind == "bool":
        return bool(value)
    if kind == "decimal":
        return Decimal(str(value))
    if kind == "json":
        return value
    return str(value)


def verify_deleted(endpoint: str, *, path=None, natural=None) -> None:
    """删除类接口：行不存在或 deleted=TRUE 视为删除成功。"""
    contract = contracts().get(endpoint)
    assert contract, f"缺少写接口契约：{endpoint}"
    try:
        row = _locate(contract, None, path, natural)
    except AssertionError:
        return  # 物理删除或已查不到
    columns = _table_columns(contract["table"])
    if "deleted" in columns:
        assert row.get("deleted") is True, f"{endpoint} 删除未生效：deleted={row.get('deleted')}"
    else:
        raise AssertionError(f"{endpoint} 声称删除但行仍存在（表无 deleted 列）")


def verify_write(endpoint: str, payload: dict, *, response=None, path=None, natural=None) -> dict:
    contract = contracts().get(endpoint)
    assert contract, f"缺少写接口契约：{endpoint}（请补 data/write_contracts.yaml）"
    row = _locate(contract, response, path, natural)

    for req_key, spec in (contract.get("fields") or {}).items():
        if req_key not in payload:
            assert spec.get("optional"), f"{endpoint} 请求缺少字段 {req_key}（契约要求必填）"
            continue
        column = spec["column"]
        expected = _convert(payload[req_key], spec.get("type", "string"))
        actual = row.get(column)
        if isinstance(actual, Decimal) or isinstance(expected, Decimal):
            ok = Decimal(str(actual)) == Decimal(str(expected))
        elif isinstance(expected, bool):
            ok = bool(actual) == expected
        else:
            ok = str(actual) == str(expected)
        assert ok, f"{endpoint} 字段 {req_key} 未正确落库：DB {column}={actual!r}，请求={payload[req_key]!r}"

    for special in contract.get("special") or []:
        source = special.get("json_from")
        if source and source in payload:
            actual = row.get(special["column"])
            expected = payload[source] or []
            assert (actual or []) == expected, (
                f"{endpoint} JSONB 字段 {special['column']} 不一致：DB={actual!r}，请求={expected!r}"
            )

    for column in contract.get("generated") or []:
        if column in row:
            assert row[column] not in (None, ""), f"{endpoint} 生成列 {column} 为空"
    return row
