"""统一日志：同时写控制台与 artifacts/logs/automation.log。"""
from __future__ import annotations

import logging
import sys
from pathlib import Path

from config.settings import settings


def get_logger(name: str = "automation") -> logging.Logger:
    logger = logging.getLogger(name)
    if logger.handlers:
        return logger

    logger.setLevel(logging.INFO)
    fmt = logging.Formatter(
        "%(asctime)s | %(levelname)-7s | %(name)s | %(message)s",
        datefmt="%H:%M:%S",
    )

    stream = logging.StreamHandler(sys.stdout)
    stream.setFormatter(fmt)
    logger.addHandler(stream)

    log_dir = Path(settings.artifacts_dir) / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    file_handler = logging.FileHandler(log_dir / "automation.log", encoding="utf-8")
    file_handler.setFormatter(fmt)
    logger.addHandler(file_handler)

    logger.propagate = False
    return logger
