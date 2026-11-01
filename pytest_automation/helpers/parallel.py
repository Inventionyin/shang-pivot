"""并行执行辅助：worker 识别与数据分片。

xdist 会为每个 worker 注入 PYTEST_XDIST_WORKER（master / gw0 / gw1…）与
PYTEST_XDIST_WORKER_COUNT。这里统一读取，供库存分片等使用；
PARALLEL_SHARDING=0 可关闭分片、回退到串行行为（用于定位问题）。
"""
from __future__ import annotations

import os
import re


def worker_id() -> str:
    return os.getenv("PYTEST_XDIST_WORKER", "master")


def worker_count() -> int:
    try:
        return max(1, int(os.getenv("PYTEST_XDIST_WORKER_COUNT", "1")))
    except ValueError:
        return 1


def worker_index() -> int:
    match = re.fullmatch(r"gw(\d+)", worker_id())
    return int(match.group(1)) if match else 0


def sharding_enabled() -> bool:
    return os.getenv("PARALLEL_SHARDING", "1") != "0"


def should_shard() -> bool:
    """只有多 worker 且未显式关闭时才分片；串行路径保持原行为。"""
    return worker_count() > 1 and sharding_enabled()
