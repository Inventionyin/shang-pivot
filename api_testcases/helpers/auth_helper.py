"""认证辅助：注册+登录一站式方法"""
from core.api_client import ApiClient
from core.auth_manager import AuthManager


class AuthHelper:
    """认证操作辅助类"""

    def __init__(self, client: ApiClient):
        self.client = client

    def register(self, account: str, password: str, nickname: str = None) -> dict:
        resp = self.client.post("/v1/auth/register", json={
            "account": account,
            "password": password,
            "nickname": nickname or account.split("@")[0],
        })
        assert resp.status_code == 200, f"注册失败: {resp.text}"
        return resp.json()["data"]

    def login(self, account: str, password: str) -> AuthManager:
        resp = self.client.post("/v1/auth/login", json={
            "account": account,
            "password": password,
        })
        assert resp.status_code == 200, f"登录失败: {resp.text}"
        return AuthManager.from_login_response(resp.json()["data"])

    def register_and_login(self, account: str, password: str, nickname: str = None) -> AuthManager:
        self.register(account, password, nickname)
        return self.login(account, password)