"""配置加载：环境 YAML + 环境变量覆盖。

用法::

    TEST_ENV=demo  python -m pytest
    ADMIN_PASSWORD=... python -m pytest -m ui

优先级：环境变量 > config/env/<TEST_ENV>.yaml > 内置默认值。
凭据（管理端密码）只从环境变量读取，不写入任何文件。
"""
from __future__ import annotations

import os
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent


def _load_local_env() -> None:
    """加载本地凭据文件（均已 gitignore，不提交）。

    优先级：真实环境变量 > .env.local > .env。
    这样在 VS Code 测试面板/终端直接跑 pytest 时，也能拿到管理端密码，
    不必每次手工 export。
    """
    for name in (".env", ".env.local"):
        path = ROOT / name
        if not path.exists():
            continue
        for raw in path.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            key = key.strip()
            value = value.strip().strip('"').strip("'")
            # setdefault：命令行/CI 注入的环境变量优先
            os.environ.setdefault(key, value)


_load_local_env()


class Settings:
    """全局配置单例。"""

    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._load()
        return cls._instance

    def _load(self) -> None:
        self.env = os.getenv("TEST_ENV", "demo").strip() or "demo"
        path = Path(__file__).parent / "env" / f"{self.env}.yaml"
        if not path.exists():
            raise FileNotFoundError(
                f"找不到环境配置 {path}；TEST_ENV 可选值见 config/env/ 目录"
            )
        with open(path, "r", encoding="utf-8") as f:
            self._data = yaml.safe_load(f) or {}

    # ---------- 接口地址 ----------
    @property
    def api_mall_base(self) -> str:
        return (
            os.getenv("MALL_BASE_URL")
            or os.getenv("SHANG_MALL_BASE_URL")
            or (self._data.get("api") or {}).get("mall_base")
            or "http://127.0.0.1:8086"
        ).rstrip("/")

    @property
    def api_admin_base(self) -> str:
        return (
            os.getenv("ADMIN_BASE_URL")
            or os.getenv("SHANG_ADMIN_BASE_URL")
            or (self._data.get("api") or {}).get("admin_base")
            or "http://127.0.0.1:8086"
        ).rstrip("/")

    # ---------- UI 地址 ----------
    @property
    def ui_mall_url(self) -> str:
        return (
            os.getenv("UI_MALL_URL")
            or (self._data.get("ui") or {}).get("mall_url")
            or self.api_mall_base
        ).rstrip("/")

    @property
    def ui_admin_url(self) -> str:
        return (
            os.getenv("UI_ADMIN_URL")
            or (self._data.get("ui") or {}).get("admin_url")
            or self.api_admin_base
        ).rstrip("/")

    # ---------- 行为 ----------
    @property
    def timeout(self) -> int:
        return int(os.getenv("TIMEOUT", (self._data.get("timeout") or 30)))

    @property
    def ui_timeout_ms(self) -> int:
        return int(
            os.getenv("UI_TIMEOUT_MS", (self._data.get("ui_timeout_ms") or 15000))
        )

    @property
    def run_ui(self) -> bool:
        return os.getenv("RUN_UI", "1") == "1"

    @property
    def artifacts_dir(self) -> Path:
        configured = os.getenv("ARTIFACTS_DIR")
        path = Path(configured) if configured else ROOT / "artifacts"
        path.mkdir(parents=True, exist_ok=True)
        return path

    # ---------- 认证 ----------
    @property
    def sms_code(self) -> str:
        auth = self._data.get("auth") or {}
        return os.getenv("TEST_SMS_CODE", str(auth.get("sms_code") or "123456"))

    @property
    def customer_phone(self) -> str:
        auth = self._data.get("auth") or {}
        return os.getenv("TEST_PHONE", str(auth.get("customer_phone") or "13900000009"))

    @property
    def admin_username(self) -> str:
        auth = self._data.get("auth") or {}
        return os.getenv("ADMIN_USERNAME", str(auth.get("admin_username") or "qa"))

    @property
    def admin_password(self) -> str:
        """只从环境变量读取；未设置时返回空串，由夹具决定 skip。"""
        return os.getenv("ADMIN_PASSWORD", "")


settings = Settings()
