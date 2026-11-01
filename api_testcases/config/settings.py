"""配置加载器：多环境切换 + 环境变量覆盖"""
import os
import yaml
from pathlib import Path


class Settings:
    """全局配置单例"""

    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._load()
        return cls._instance

    def _load(self):
        env = os.getenv("TEST_ENV", "dev")
        config_dir = Path(__file__).parent
        config_path = config_dir / "env" / f"{env}.yaml"

        with open(config_path, "r", encoding="utf-8") as f:
            self._config = yaml.safe_load(f) or {}

    @property
    def base_url(self) -> str:
        return os.getenv("BASE_URL", self._config.get("base_url", "http://localhost:8086"))

    @property
    def admin_base(self) -> str:
        """管理端根地址（/admin/** 打这里）。"""
        return (os.getenv("ADMIN_BASE_URL")
                or self._config.get("admin_base")
                or "http://YOUR_SERVER_IP:60085").rstrip("/")

    @property
    def mall_base(self) -> str:
        """C 端根地址（/api/** 打这里）。"""
        return (os.getenv("MALL_BASE_URL")
                or self._config.get("mall_base")
                or "http://YOUR_SERVER_IP:60084").rstrip("/")

    @property
    def timeout(self) -> int:
        return int(os.getenv("TIMEOUT", self._config.get("timeout", 30)))

    @property
    def db_config(self) -> dict:
        return self._config.get("db", {})

    @property
    def redis_config(self) -> dict:
        return self._config.get("redis", {})

    @property
    def auth_config(self) -> dict:
        return self._config.get("auth", {})


settings = Settings()