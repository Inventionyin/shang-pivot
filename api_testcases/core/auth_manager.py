"""Token 管理器：获取、缓存、自动刷新"""
import time


class AuthManager:
    """管理测试用户的 Token 生命周期"""

    def __init__(self, token: str = None, user_id: int = None, expires_in: int = 7200):
        self._token = token
        self._user_id = user_id
        self._expires_in = expires_in
        self._fetched_at = time.time() if token else 0

    @classmethod
    def from_login_response(cls, data: dict) -> "AuthManager":
        return cls(
            token=data["token"],
            user_id=data["user"]["id"],
            expires_in=data.get("expiresIn", 7200),
        )

    @property
    def token(self) -> str:
        return self._token

    @property
    def user_id(self) -> int:
        return self._user_id

    @property
    def is_valid(self) -> bool:
        if not self._token:
            return False
        elapsed = time.time() - self._fetched_at
        # 提前 5 分钟判定过期
        return elapsed < (self._expires_in - 300)

    def invalidate(self):
        self._token = None
        self._fetched_at = 0