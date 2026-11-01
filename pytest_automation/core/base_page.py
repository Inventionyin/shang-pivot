"""页面对象基类：统一导航、等待、步骤截图与失败截图。"""
from __future__ import annotations

from contextlib import contextmanager

from playwright.sync_api import Page

from config.settings import settings
from core.logger import get_logger

log = get_logger("ui")


class BasePage:
    def __init__(self, page: Page, base_url: str):
        self.page = page
        self.base_url = base_url.rstrip("/")
        self.timeout = settings.ui_timeout_ms

    def open(self, path: str = "/") -> None:
        with self.step(f"打开 {path}"):
            url = f"{self.base_url}{path}"
            self.page.goto(url, wait_until="domcontentloaded", timeout=max(self.timeout, 30000))
            try:
                self.page.wait_for_load_state("networkidle", timeout=min(self.timeout, 15000))
            except Exception:
                # 长轮询/慢资源不阻塞页面对象，后续用元素级等待兜底
                pass
            log.info("打开 %s", url)

    @contextmanager
    def step(self, name: str):
        """有录制器时：Allure 步骤 + 动作后截图；无录制器时直接执行。"""
        recorder = getattr(self.page, "_step_recorder", None)
        if recorder is None:
            yield
            return
        with recorder.step(name):
            yield

    def capture(self, name: str) -> None:
        recorder = getattr(self.page, "_step_recorder", None)
        if recorder is not None:
            recorder.capture(name)

    def url(self) -> str:
        return self.page.url

    def wait_path(self, fragment: str) -> None:
        self.page.wait_for_url(f"**{fragment}**", timeout=max(self.timeout, 30000))

    def text(self) -> str:
        return self.page.locator("body").inner_text()

    def screenshot(self, name: str) -> None:
        path = settings.artifacts_dir / "ui" / f"{name}.png"
        path.parent.mkdir(parents=True, exist_ok=True)
        self.page.screenshot(path=str(path), full_page=True)
        log.info("截图 %s", path)
