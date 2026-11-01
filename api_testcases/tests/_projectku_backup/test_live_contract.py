"""Strict, low-side-effect checks against the deployed live contract.

Run with ``BASE_URL=http://103.236.91.136:60080/api``.  Authenticated checks
use APP_TEST_ACCOUNT/APP_TEST_PASSWORD at runtime, never repository files.
"""
import json
import os
from pathlib import Path

import pytest

from core.api_client import ApiClient


LIVE_ROOT = os.getenv("APP_BASE_URL", "http://103.236.91.136:60080").rstrip("/")
API_BASE = os.getenv("BASE_URL", f"{LIVE_ROOT}/api").rstrip("/")


def _json(response):
    assert "json" in response.headers.get("Content-Type", "").lower(), response.text[:500]
    return response.json()


@pytest.fixture(scope="module")
def live_client():
    client = ApiClient(base_url=API_BASE, allure_logging=False)
    yield client
    client.close()


@pytest.fixture(scope="module")
def live_token(live_client):
    account = os.getenv("APP_TEST_ACCOUNT")
    password = os.getenv("APP_TEST_PASSWORD")
    if not account or not password:
        pytest.skip("未设置 APP_TEST_ACCOUNT/APP_TEST_PASSWORD；不会在生产环境自动创建测试账号")
    response = live_client.post("/v1/auth/login", json={"account": account, "password": password})
    assert response.status_code == 200, response.text[:1000]
    payload = _json(response)
    token = payload.get("data", {}).get("token")
    assert token, "登录响应缺少 data.token"
    return token


@pytest.mark.P0
@pytest.mark.smoke
def test_live_health(live_client):
    response = live_client.get("/actuator/health")
    assert response.status_code == 200
    payload = _json(response)
    assert payload.get("status") == "UP"


@pytest.mark.P0
@pytest.mark.smoke
def test_live_openapi_has_expected_contract(live_client):
    response = live_client.get("/v3/api-docs")
    assert response.status_code == 200
    payload = _json(response)
    assert payload.get("openapi", "").startswith("3.")
    assert "/v1/products" in payload.get("paths", {})
    assert len(payload.get("paths", {})) >= 40


@pytest.mark.P0
@pytest.mark.smoke
@pytest.mark.zt_case("GET_V1_PRODUCTS-001")
def test_public_products_contract(live_client):
    response = live_client.get("/v1/products", params={"page": 1, "size": 1})
    assert response.status_code == 200
    payload = _json(response)
    assert payload.get("code") == 200
    assert isinstance(payload.get("data"), list)
    if payload["data"]:
        product = payload["data"][0]
        for field in ("id", "name", "price", "stock", "status"):
            assert field in product


@pytest.mark.P0
@pytest.mark.security
@pytest.mark.zt_case("GET_V1_ME-002")
def test_protected_me_rejects_missing_auth(live_client):
    response = live_client.get("/v1/me")
    # Live service currently uses HTTP 400 with error.code=UNAUTHORIZED;
    # this is recorded as the observed baseline because OpenAPI omits errors.
    assert response.status_code == 400, response.text[:1000]
    payload = _json(response)
    assert payload.get("error", {}).get("code") == "UNAUTHORIZED"


@pytest.mark.P1
@pytest.mark.regression
@pytest.mark.zt_case(case_ids=["GET_V1_ME-001", "GET_V1_ORDERS-001"])
def test_authenticated_me_and_orders_contract(live_client, live_token):
    live_client.set_token(live_token)
    me = live_client.get("/v1/me")
    assert me.status_code == 200
    me_payload = _json(me)
    assert me_payload.get("code") == 200
    assert me_payload.get("data", {}).get("id")
    orders = live_client.get("/v1/orders", params={"page": 1, "size": 10})
    assert orders.status_code == 200
    orders_payload = _json(orders)
    assert orders_payload.get("code") == 200
    assert isinstance(orders_payload.get("data"), list)


@pytest.mark.P1
@pytest.mark.regression
def test_inventory_matches_live_openapi():
    contract = json.loads((Path(__file__).parents[2] / "../docs/openapi-v3-api-docs.live.json").resolve().read_text())
    assert len(contract.get("paths", {})) == 49
