"""接口断言：把「HTTP 状态 + 业务码 + 响应结构」收成一组可读函数。

尚品汇约定：
- 成功：HTTP 200 + ``code == 0``
- 业务失败：HTTP 200 + ``code != 0``（如 40410 商品不可用、40902 状态非法）
- Bean 校验失败：HTTP 400 + ``code == 40000``
- 未认证：HTTP 401 + ``code == 40100/40103``
"""
from __future__ import annotations

import re

import requests


def payload(resp: requests.Response) -> dict:
    try:
        data = resp.json()
    except ValueError as exc:  # pragma: no cover - 只在服务端返回非 JSON 时触发
        raise AssertionError(
            f"响应不是 JSON：HTTP {resp.status_code}，body={resp.text[:500]!r}"
        ) from exc
    assert isinstance(data, dict), f"响应体应为对象，实际 {type(data).__name__}"
    return data


def ok(resp: requests.Response) -> dict:
    """断言成功并返回 ``data``。"""
    body = payload(resp)
    assert resp.status_code == 200, (
        f"HTTP 期望 200 实际 {resp.status_code}；body={body}"
    )
    assert body.get("code") == 0, f"业务码期望 0 实际 {body.get('code')}；{body.get('msg')}"
    return body.get("data")


def error(
    resp: requests.Response,
    code: int = None,
    http_status: int = None,
) -> dict:
    """断言业务失败，返回完整响应体。"""
    body = payload(resp)
    if http_status is not None:
        assert resp.status_code == http_status, (
            f"HTTP 期望 {http_status} 实际 {resp.status_code}；body={body}"
        )
    if code is not None:
        assert body.get("code") == code, (
            f"业务码期望 {code} 实际 {body.get('code')}；msg={body.get('msg')}"
        )
    assert body.get("code") != 0, f"期望失败但业务码为 0：{body}"
    return body


def assert_message_contains(resp: requests.Response, keyword: str) -> dict:
    body = payload(resp)
    assert keyword in str(body.get("msg", "")), (
        f"提示信息应包含 {keyword!r}，实际 {body.get('msg')!r}"
    )
    return body


def assert_pagination(data: dict, page: int = None, size: int = None) -> None:
    assert isinstance(data, dict), f"分页 data 应为对象，实际 {type(data).__name__}"
    assert "records" in data and isinstance(data["records"], list), "分页缺少 records 列表"
    assert "total" in data, "分页缺少 total"
    if page is not None and size is not None:
        assert len(data["records"]) <= size, (
            f"本页返回 {len(data['records'])} 条，超过 size={size}"
        )
        assert int(data["total"]) >= len(data["records"]), "total 小于本页条数"


def assert_trace_id(resp: requests.Response) -> None:
    """L4：响应体 traceId 为 32 位十六进制，且与响应头一致。"""
    body = payload(resp)
    trace_id = str(body.get("traceId", ""))
    assert re.fullmatch(r"[0-9a-f]{32}", trace_id), f"traceId 格式异常：{trace_id!r}"
    header = resp.headers.get("X-Trace-Id")
    if header:
        assert header == trace_id, f"响应头 traceId {header} 与 body {trace_id} 不一致"


def assert_data_keys(data: dict, *keys: str) -> None:
    missing = [k for k in keys if k not in data]
    assert not missing, f"响应 data 缺少字段 {missing}；实际字段 {sorted(data)}"
