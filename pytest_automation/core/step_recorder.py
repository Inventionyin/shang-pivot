"""UI 步骤证据：每一步动作后截图，并写入 Allure 与步骤清单。

目录：``artifacts/ui/steps/<测试 nodeid>/``
- ``01-打开首页.png``、``02-点击登录.png`` ... 每步一张整页截图
- ``steps.json`` 步骤序号、名称、截图路径、URL、时间
"""
from __future__ import annotations

import json
import re
from contextlib import contextmanager
from datetime import datetime
from pathlib import Path

from config.settings import settings
from core.logger import get_logger

try:
    import allure  # type: ignore
except ImportError:  # pragma: no cover
    allure = None

log = get_logger("steps")


def safe_name(text: str, limit: int = 80) -> str:
    text = re.sub(r"[^\w\u4e00-\u9fff.-]+", "_", str(text)).strip("_")
    return (text or "step")[:limit]


class StepRecorder:
    def __init__(self, page, test_name: str):
        self.page = page
        self.test_name = test_name
        self.dir = Path(settings.artifacts_dir) / "ui" / "steps" / safe_name(test_name, 150)
        self.dir.mkdir(parents=True, exist_ok=True)
        self.steps = []

    @contextmanager
    def step(self, name: str):
        """Allure 步骤 + 步骤结束后截图（动作失败也会留证）。"""
        with allure.step(name) if allure is not None else _nullcontext():
            try:
                yield
            finally:
                self.capture(name)

    def capture(self, name: str) -> Path | None:
        index = len(self.steps) + 1
        filename = f"{index:02d}-{safe_name(name)}.png"
        path = self.dir / filename
        try:
            self.page.screenshot(path=str(path), full_page=True)
            record = {
                "index": index,
                "name": name,
                "screenshot": str(path),
                "url": self.page.url,
                "at": datetime.now().isoformat(timespec="seconds"),
            }
            self.steps.append(record)
            if allure is not None:
                allure.attach.file(
                    str(path),
                    name=f"{index:02d} {name}",
                    attachment_type=allure.attachment_type.PNG,
                )
            return path
        except Exception as exc:  # noqa: BLE001 - 留证失败不能掩盖真实用例结果
            log.warning("步骤截图失败 [%s] %s: %s", self.test_name, name, exc)
            return None

    def save_manifest(self) -> None:
        payload = {"test": self.test_name, "total_steps": len(self.steps), "steps": self.steps}
        (self.dir / "steps.json").write_text(
            json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
        )


class _nullcontext:
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False
