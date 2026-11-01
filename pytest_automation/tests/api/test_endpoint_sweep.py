"""全接口巡检：对端点模型中的每个接口执行安全/逆向/边界/异常检查。

数据由 `scripts/build_endpoint_matrix.py` 从 `api_testcases/artifacts/endpoint-model.json` 生成，
覆盖全部 135 个端点（不区分核心与否）：

| 检查 | 说明 | 预期 |
|---|---|---|
| security_anonymous | 受保护端点匿名访问 | 401 |
| security_role | 会员 token 打管理端 / 管理端 token 打 C 端 | 401/403 |
| path_param_type | 路径参数传非数字 `abc` | 4xx，不得 500 |
| detail_not_found | 详情端点传不存在 ID | 40400/40410 |
| list_boundary | 列表端点 page=0/-1、size=0/1000 | 不得 5xx |
| malformed_json | 截断的 JSON 请求体 | 400，不得 500 |
| empty_body | 空对象请求体 | 不得 5xx |

所有请求均为「拒绝类/只读类」，不会创建或修改业务数据。
报告里每个端点一个 feature（`METHOD /path`），可直接看到单接口的全部检查结果。
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from core.api_client import ApiClient
from config.settings import settings
from helpers.auth_helper import new_customer

try:
    import allure  # type: ignore
except ImportError:  # pragma: no cover
    allure = None

pytestmark = [pytest.mark.api]

DATA_FILE = Path(__file__).resolve().parents[2] / "data" / "endpoint_matrix.json"
CASES = json.loads(DATA_FILE.read_text(encoding="utf-8")) if DATA_FILE.exists() else []

@pytest.fixture(scope="module")
def sweep_customer():
    """巡检专用会员：模块级只注册一次，避免 384 条用例各建一个账号。"""
    cust = new_customer()
    yield cust
    cust.client.close()


@pytest.fixture(scope="module")
def clients(anonymous, sweep_customer, admin):
    """巡检专用客户端：关闭 GET 自动重试，保证 500 能拿到响应体与 traceId 证据。"""
    from requests.adapters import HTTPAdapter

    def make(base: str, token: str = "") -> ApiClient:
        client = ApiClient(base, token=token)
        adapter = HTTPAdapter(max_retries=0)
        client.session.mount("http://", adapter)
        client.session.mount("https://", adapter)
        return client

    mapping = {
        "anonymous": make(settings.api_mall_base),
        "customer": make(settings.api_mall_base, sweep_customer.token),
        "admin": make(settings.api_admin_base, admin.token),
    }
    yield mapping
    for item in mapping.values():
        item.close()


def _client(case, clients) -> ApiClient:
    """检查项可指定另一个角色的 token（角色越权检查用），否则按端点 auth。"""
    role = case.get("token") or case["auth"]
    return clients.get(role, clients["anonymous"])


def _path_with(case, value: str) -> str:
    path = case["path"]
    while "{" in path:
        start, end = path.find("{"), path.find("}")
        path = path[:start] + value + path[end + 1:]
    return path


def _not_5xx(resp, hint: str) -> dict:
    try:
        body = resp.json()
    except ValueError:
        body = {}
    code = body.get("code") if isinstance(body, dict) else None
    assert resp.status_code < 500, f"{hint}：HTTP {resp.status_code}"
    assert code != 50000, f"{hint}：业务码 50000（系统异常）"
    return body if isinstance(body, dict) else {}


def _ids(case) -> str:
    return f"{case['method']} {case['path']}"


def _annotate(case) -> None:
    if allure is None:
        return
    endpoint = _ids(case)
    allure.dynamic.parent_suite("接口巡检")
    allure.dynamic.suite(endpoint)
    allure.dynamic.feature(endpoint)
    allure.dynamic.story(case["title"])
    allure.dynamic.title(f"{case['title']}｜{endpoint}")


@pytest.mark.parametrize(
    "case_index",
    range(len(CASES)),
    ids=[f"{c['check']}｜{c['method']} {c['path']}" for c in CASES],
)
def test_endpoint_sweep(case_index, clients):
    case = CASES[case_index]
    _annotate(case)
    check = case["check"]
    method = case["method"]
    hint = f"{check} {method} {case['path']}"

    if check == "security_anonymous":
        client = clients["anonymous"]
        kwargs = {"params": {"page": 1, "size": 1}} if method == "GET" else {"json": {}}
        resp = client.request(method, _path_with(case, "1"), **kwargs)
        assert resp.status_code == 401, f"{hint}：匿名访问应 401，实际 {resp.status_code}"
        body = resp.json()
        assert body.get("code") in (40100, 40103), f"{hint}：业务码 {body.get('code')}"
        return

    client = _client(case, clients)

    if check == "security_role":
        kwargs = {"params": {"page": 1, "size": 1}} if method == "GET" else {"json": {}}
        resp = client.request(method, _path_with(case, "1"), **kwargs)
        assert resp.status_code in (401, 403), (
            f"{hint}：跨角色访问应 401/403，实际 {resp.status_code}"
        )
        return

    if check == "path_param_type":
        resp = client.request(
            method,
            _path_with(case, case["path_params"]),
            params={"page": 1, "size": 1} if method == "GET" else None,
            json={} if method != "GET" else None,
        )
        _not_5xx(resp, hint)
        return

    if check == "detail_not_found":
        resp = client.request(method, _path_with(case, case["path_params"]))
        body = _not_5xx(resp, hint)
        # 列表型子资源（如商品的评价）对不存在的父资源返回空集是允许的
        empty_ok = (
            resp.status_code == 200
            and isinstance(body.get("data"), dict)
            and body["data"].get("records") == []
        )
        assert resp.status_code == 404 or body.get("code") in (40400, 40410) or empty_ok, (
            f"{hint}：不存在资源应 404/40400 或空集，实际 status={resp.status_code} code={body.get('code')}"
        )
        return

    if check == "malformed_json":
        resp = client.request(
            method,
            _path_with(case, "1"),
            data='{"broken":',
            headers={"Content-Type": "application/json"},
        )
        body = _not_5xx(resp, hint)
        assert resp.status_code == 400 or body.get("code") == 40000, (
            f"{hint}：畸形 JSON 应 400，实际 status={resp.status_code} code={body.get('code')}"
        )
        return

    if check == "empty_body":
        resp = client.request(method, _path_with(case, "1"), json={})
        _not_5xx(resp, hint)
        return

    if check == "positive_login":
        if case["path"].startswith("/admin"):
            if not settings.admin_password:
                pytest.skip("未设置 ADMIN_PASSWORD，跳过管理端登录正向")
            payload = {
                "username": settings.admin_username,
                "password": settings.admin_password,
            }
        else:
            payload = {
                "phone": settings.customer_phone,
                "code": settings.sms_code,
                "clientType": "web",
            }
        resp = clients["anonymous"].post(case["path"], json=payload)
        body = _not_5xx(resp, hint)
        assert resp.status_code == 200 and body.get("code") == 0, (
            f"{hint}：正确凭证应登录成功，实际 status={resp.status_code} code={body.get('code')}"
        )
        assert (body.get("data") or {}).get("accessToken"), f"{hint}：响应缺少 accessToken"
        return

    if check == "positive_list":
        resp = client.request(method, case["path"], params={"page": 1, "size": 20})
        body = _not_5xx(resp, hint)
        assert resp.status_code == 200 and body.get("code") == 0, (
            f"{hint}：正向列表应 200/code=0，实际 status={resp.status_code} code={body.get('code')}"
        )
        return

    if check == "bulk_page":
        resp = client.request(method, case["path"], params={"page": 1, "size": 100})
        body = _not_5xx(resp, hint)
        assert resp.status_code == 200, f"{hint}：大分页应 200，实际 {resp.status_code}"
        data = body.get("data")
        if isinstance(data, dict):
            records = data.get("records") or []
        elif isinstance(data, list):
            records = data
        else:
            records = []
        assert len(records) <= 100, f"{hint}：返回 {len(records)} 条，超过 size=100"
        return

    if check == "positive_detail":
        sibling = case["sibling"]
        listing = client.request("GET", sibling, params={"page": 1, "size": 20})
        if listing.status_code != 200:
            pytest.skip(f"同级列表 {sibling} 不可用（HTTP {listing.status_code}），无法取样本 ID")
        try:
            payload = listing.json().get("data")
        except ValueError:
            pytest.skip("同级列表响应非 JSON")
        if isinstance(payload, dict):
            records = payload.get("records") or []
        elif isinstance(payload, list):
            records = payload
        else:
            records = []
        if not records:
            pytest.skip(f"同级列表 {sibling} 无数据，跳过详情正向")
        record = records[0] if isinstance(records[0], dict) else {}
        value = None
        for key in (
            case["id_key"], "id", "spuId", "orderId", "customerId", "skuId",
            "userId", "roleId", "code", "objectKey", "afterSaleNo",
        ):
            if record.get(key) not in (None, ""):
                value = record[key]
                break
        if value is None:
            pytest.skip("无法从列表记录提取 ID 字段")
        resp = client.request("GET", _path_with(case, str(value)))
        body = _not_5xx(resp, hint)
        assert resp.status_code == 200 and body.get("code") == 0, (
            f"{hint}：详情应 200/code=0，实际 status={resp.status_code} code={body.get('code')}"
        )
        return

    if check == "list_boundary":
        for params in (
            {"page": 0, "size": 0},
            {"page": -1, "size": 1000},
            {"page": 1, "size": 1000},
        ):
            resp = client.request(method, case["path"], params=params)
            body = _not_5xx(resp, f"{hint} {params}")
            if resp.status_code == 200:
                assert isinstance(body, dict), f"{hint} {params}：200 但响应不是对象"
        return

    pytest.fail(f"未知检查项：{check}")
