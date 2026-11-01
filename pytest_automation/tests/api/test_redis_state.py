"""中间件状态用例：Redis 验证码 TTL/冷却、登录失败锁定、令牌黑名单。

这些断言直接读 Redis，验证「接口行为」与「缓存事实」一致；均使用随机键并在用例内清理。
标记 serial：登录失败计数会影响同账号的并行用例。
"""
from __future__ import annotations

import os
import time
import uuid

import pytest

from core.assertions import error, ok
from helpers.auth_helper import login_admin, new_customer
from helpers.data_factory import unique_phone
from config.settings import settings
from core.api_client import ApiClient

pytestmark = [pytest.mark.api, pytest.mark.serial]

redis = pytest.importorskip("redis")


def _client():
    host = os.getenv("REDIS_HOST", "127.0.0.1")
    port = int(os.getenv("REDIS_PORT", "6379"))
    db = int(os.getenv("REDIS_DB", "0"))
    client = redis.Redis(host=host, port=port, db=db, decode_responses=True,
                         socket_connect_timeout=2, socket_timeout=2)
    try:
        client.ping()
    except Exception as exc:  # noqa: BLE001
        pytest.skip(f"Redis 不可用：{exc}")
    return client


@pytest.fixture(scope="module")
def cache():
    client = _client()
    yield client
    client.close()


class TestSmsCode:
    def test_code_and_cooldown_ttl(self, anonymous, cache):
        """发送验证码：code 5 分钟、cooldown 60 秒，登录成功后 code 立即失效。"""
        phone = unique_phone()
        cache.delete(f"sms:phone:{phone}", f"sms:cooldown:{phone}")
        data = ok(anonymous.post("/api/auth/sms/send", json={"phone": phone}))
        assert data["cooldownSec"] == 60

        code_key = f"sms:phone:{phone}"
        cooldown_key = f"sms:cooldown:{phone}"
        assert cache.get(code_key) == "123456"
        code_ttl = cache.ttl(code_key)
        cooldown_ttl = cache.ttl(cooldown_key)
        assert 240 <= code_ttl <= 300, f"验证码 TTL 应约 5 分钟，实际 {code_ttl}"
        assert 0 < cooldown_ttl <= 60, f"冷却 TTL 应 ≤60 秒，实际 {cooldown_ttl}"

        login = ok(anonymous.post("/api/auth/login", json={
            "phone": phone, "code": "123456", "clientType": "web",
        }))
        assert login["accessToken"]
        assert cache.get(code_key) is None, "登录成功后验证码应删除"
        cache.delete(cooldown_key)

    def test_cooldown_rejects_second_send(self, anonymous, cache):
        """60 秒内重复发送：400，提示剩余秒数，不刷新验证码。"""
        phone = unique_phone()
        cache.delete(f"sms:phone:{phone}", f"sms:cooldown:{phone}")
        ok(anonymous.post("/api/auth/sms/send", json={"phone": phone}))
        before = cache.get(f"sms:phone:{phone}")

        body = error(anonymous.post("/api/auth/sms/send", json={"phone": phone}), code=40000)
        assert "频繁" in body["msg"] and "秒" in body["msg"]
        assert cache.get(f"sms:phone:{phone}") == before
        cache.delete(f"sms:phone:{phone}", f"sms:cooldown:{phone}")


class TestAdminLockout:
    def test_five_failures_lock_account(self, admin, cache):
        """连续 5 次密码错误后第 6 次锁定（40104），锁定键 15 分钟。"""
        username = f"lock_{uuid.uuid4().hex[:10]}"
        key = f"login:fail:{username}"
        cache.delete(key)
        payload = {"username": username, "password": "wrong-password"}
        try:
            for index in range(5):
                resp = admin.post("/admin/auth/login", json=payload)
                body = error(resp, code=40101)
                assert body["code"] == 40101, f"第 {index + 1} 次应为密码错误"
            locked = error(admin.post("/admin/auth/login", json=payload), code=40104)
            assert "15" in locked["msg"] or "锁定" in locked["msg"]
            ttl = cache.ttl(key)
            assert 0 < ttl <= 15 * 60, f"锁定 TTL 应 ≤15 分钟，实际 {ttl}"
        finally:
            cache.delete(key)

    def test_success_clears_fail_counter(self, admin, cache):
        """成功登录清零失败计数（用真实管理员账号，先造 1 次失败）。"""
        from config.settings import settings

        username = settings.admin_username
        key = f"login:fail:{username}"
        cache.delete(key)
        error(admin.post("/admin/auth/login", json={
            "username": username, "password": "wrong-password",
        }), code=40101)
        assert cache.get(key) == "1"
        ok(admin.post("/admin/auth/login", json={
            "username": username, "password": settings.admin_password,
        }))
        assert cache.get(key) is None


class TestRefreshAndAdminBlacklist:
    def test_customer_refresh_blacklists_old_refresh(self, cache):
        """刷新令牌后旧 refresh jti 进入黑名单。"""
        session = new_customer()
        try:
            before = set(cache.keys("jwt:blacklist:*"))
            data = ok(session.client.post("/api/auth/refresh", json={
                "refreshToken": session.refresh_token,
            }))
            assert data["accessToken"] and data["refreshToken"] != session.refresh_token
            after = set(cache.keys("jwt:blacklist:*"))
            assert after - before, "刷新后旧 refresh 未进入黑名单"
        finally:
            session.client.close()

    def test_admin_logout_blacklists_token(self, cache):
        """管理端退出：旧 access token 立即失效并写入黑名单。"""
        client = ApiClient(settings.api_admin_base)
        try:
            login_admin(client)
            token = client.token
            before = set(cache.keys("jwt:blacklist:*"))
            ok(client.post("/admin/auth/logout"))
            resp = client.get("/admin/auth/me")
            assert resp.status_code in (401, 403), f"退出后仍可访问：HTTP {resp.status_code}"
            assert set(cache.keys("jwt:blacklist:*")) - before, "管理端退出未写黑名单"
            assert token
        finally:
            client.close()

    def test_admin_refresh_blacklists_old_refresh(self, cache):
        """管理端刷新：旧 refresh 进入黑名单。"""
        client = ApiClient(settings.api_admin_base)
        try:
            data = login_admin(client)
            before = set(cache.keys("jwt:blacklist:*"))
            ok(client.post("/admin/auth/refresh", json={"refreshToken": data["refreshToken"]}))
            assert set(cache.keys("jwt:blacklist:*")) - before, "管理端刷新未吊销旧 refresh"
        finally:
            client.close()

    def test_phone_alias_consumes_sms_code(self, anonymous, cache):
        """/api/auth/phone 与 /api/auth/login 等价：验证码登录后缓存键删除。"""
        phone = unique_phone()
        cache.delete(f"sms:phone:{phone}", f"sms:cooldown:{phone}")
        ok(anonymous.post("/api/auth/sms/send", json={"phone": phone}))
        data = ok(anonymous.post("/api/auth/phone", json={
            "phone": phone, "code": "123456", "clientType": "web",
        }))
        assert data["accessToken"]
        assert cache.get(f"sms:phone:{phone}") is None
        cache.delete(f"sms:cooldown:{phone}")


class TestTokenBlacklist:
    def test_logout_blacklists_access_token(self, cache):
        """退出登录后 access token 进入黑名单且立即失效。"""
        session = new_customer()
        token = session.token
        try:
            ok(session.client.post("/api/auth/logout"))
            resp = session.client.get("/api/me")
            assert resp.status_code == 401, f"退出后仍可访问：HTTP {resp.status_code}"
            keys = cache.keys("jwt:blacklist:*")
            assert keys, "Redis 中应存在 jwt:blacklist:* 键"
            assert any(cache.ttl(key) > 0 for key in keys), "黑名单键应带 TTL"
        finally:
            session.client.close()

    def test_blacklist_expires(self, cache):
        """黑名单键带过期时间，不做永久封禁。"""
        keys = cache.keys("jwt:blacklist:*")
        if not keys:
            pytest.skip("本轮尚无黑名单键")
        assert all(cache.ttl(key) > 0 for key in keys[:20])
        time.sleep(0)
