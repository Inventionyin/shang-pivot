"""安全用例：令牌篡改、越权、注入、异常参数。"""
from __future__ import annotations

import pytest

from config.settings import settings
from core.api_client import ApiClient
from core.assertions import assert_trace_id, error, ok

pytestmark = [pytest.mark.api, pytest.mark.security]


def test_tampered_token_rejected(customer):
    client = ApiClient(settings.api_mall_base, token=customer.token + "x")
    try:
        resp = client.get("/api/me")
        body = error(resp, http_status=401)
        assert body["code"] in (40100, 40103)
    finally:
        client.close()


def test_customer_token_rejected_by_admin(customer):
    client = ApiClient(settings.api_admin_base, token=customer.token)
    try:
        resp = client.get("/admin/products")
        body = error(resp, http_status=401)
        assert body["code"] in (40100, 40103)
    finally:
        client.close()


def test_login_phone_sql_injection(anonymous):
    resp = anonymous.post(
        "/api/auth/login",
        json={"phone": "' OR 1=1 --", "code": "123456", "clientType": "web"},
    )
    body = error(resp, code=40000, http_status=400)
    assert "手机号" in body["msg"]


def test_search_xss_payload_is_inert(anonymous):
    payload = "<script>alert(1)</script>"
    resp = anonymous.get("/api/search", params={"q": payload, "size": 5})
    body = ok(resp)
    assert body["records"] == []
    assert "<script>alert(1)</script>" not in resp.text


def test_oversized_page_does_not_500(anonymous):
    resp = anonymous.get("/api/products", params={"page": 1, "size": 999999})
    ok(resp)
    assert resp.status_code < 500


def test_error_response_keeps_trace_id(anonymous):
    resp = anonymous.get("/api/products/999999")
    error(resp, code=40410)
    assert_trace_id(resp)
