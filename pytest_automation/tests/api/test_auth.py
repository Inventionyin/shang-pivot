"""认证接口用例：商城短信登录、令牌生命周期、后台登录。

对应 pytest-learning 第 14/15 集：每条用例独立、可重复执行。
"""
from __future__ import annotations

import pytest

from config.settings import settings
from core.api_client import ApiClient
from core.assertions import assert_trace_id, error, ok
from helpers.auth_helper import login_customer, new_customer
from helpers.data_factory import unique_phone

pytestmark = [pytest.mark.api]


class TestCustomerAuth:
    @pytest.mark.P0
    @pytest.mark.smoke
    def test_send_sms(self, anonymous):
        """发送验证码：返回冷却秒数，演示环境固定 123456。"""
        phone = unique_phone()
        data = ok(anonymous.post("/api/auth/sms/send", json={"phone": phone}))
        assert isinstance(data.get("cooldownSec"), int)
        assert data["cooldownSec"] > 0

    @pytest.mark.P1
    def test_send_sms_rejects_bad_phone(self, anonymous):
        resp = anonymous.post("/api/auth/sms/send", json={"phone": "123"})
        body = error(resp, code=40000, http_status=400)
        assert "手机号" in body["msg"]

    @pytest.mark.P0
    @pytest.mark.smoke
    def test_login_contract(self, customer):
        """登录响应契约：token、刷新令牌、过期时间与会员资料。"""
        assert customer.token
        assert customer.refresh_token
        assert isinstance(customer.expires_in, str), "Long 字段应序列化为字符串"
        assert customer.info.get("id")
        assert customer.info.get("status") == "NORMAL"
        assert "****" in customer.info.get("phone", "")

    @pytest.mark.P1
    def test_login_wrong_code(self, anonymous):
        resp = anonymous.post(
            "/api/auth/login",
            json={"phone": unique_phone(), "code": "000000", "clientType": "web"},
        )
        body = error(resp, code=40101)
        assert "验证码" in body["msg"]

    @pytest.mark.P0
    def test_me_returns_current_customer(self, customer):
        data = ok(customer.client.get("/api/me"))
        assert str(data["id"]) == str(customer.info["id"])
        assert data["status"] == "NORMAL"

    @pytest.mark.P1
    def test_me_requires_token(self, anonymous):
        resp = anonymous.get("/api/me")
        body = error(resp, http_status=401)
        assert body["code"] in (40100, 40103)

    @pytest.mark.P1
    def test_refresh_token(self, customer):
        resp = customer.client.post(
            "/api/auth/refresh", json={"refreshToken": customer.refresh_token}
        )
        data = ok(resp)
        assert data.get("accessToken")
        assert data.get("expiresIn")

    @pytest.mark.P1
    def test_logout_revokes_token(self):
        """登出必须真正失效，不能只返回成功。"""
        cust = new_customer()
        try:
            ok(cust.client.post("/api/auth/logout"))
            resp = cust.client.get("/api/me")
            body = error(resp, http_status=401)
            assert body["code"] in (40100, 40103)
        finally:
            cust.client.close()

    @pytest.mark.P1
    def test_update_profile(self, customer):
        nickname = "自动化昵称"
        data = ok(customer.client.put("/api/me", json={"nickname": nickname}))
        assert data.get("nickname") == nickname


class TestAdminAuth:
    @pytest.mark.P0
    @pytest.mark.smoke
    def test_admin_login_and_me(self, admin):
        data = ok(admin.get("/admin/auth/me"))
        assert data.get("username") == settings.admin_username
        assert isinstance(data.get("permissions"), (list, tuple, set))
        assert data.get("menus") is not None

    @pytest.mark.P1
    @pytest.mark.serial
    def test_admin_wrong_password(self):
        client = ApiClient(settings.api_admin_base)
        try:
            resp = client.post(
                "/admin/auth/login",
                json={"username": f"no_such_user_{unique_phone()}", "password": "wrong"},
            )
            body = error(resp, code=40101)
            assert "密码" in body["msg"] or "账号" in body["msg"]
        finally:
            client.close()

    @pytest.mark.P1
    @pytest.mark.serial
    def test_admin_login_rejects_member_account(self):
        """非管理员（会员手机号+短信验证码）登录管理端必须被拒。"""
        client = ApiClient(settings.api_admin_base)
        try:
            resp = client.post(
                "/admin/auth/login",
                json={"username": "13900000009", "password": settings.sms_code},
            )
            body = error(resp)
            assert body["code"] == 40101, f"非管理员登录返回 {body['code']}"
        finally:
            client.close()

    @pytest.mark.P1
    def test_admin_requires_token(self):
        client = ApiClient(settings.api_admin_base)
        try:
            resp = client.get("/admin/products")
            body = error(resp, http_status=401)
            assert body["code"] in (40100, 40103)
        finally:
            client.close()

    @pytest.mark.P2
    def test_response_has_trace_id(self, anonymous):
        resp = anonymous.get("/api/products", params={"size": 1})
        ok(resp)
        assert_trace_id(resp)
