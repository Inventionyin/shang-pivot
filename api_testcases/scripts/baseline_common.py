#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""基线生成器的公共约定。

**时间戳粘性**：机制区产物一旦入库，复算后必须逐字节一致，否则
`git diff --exit-code`（03-版本验证与自动化.md §2 的新鲜度判定）会永远报「已过期」，门禁形同虚设。
因此所有生成器写 `generated_at` 时必须走本模块：内容未变则沿用旧值，内容真变了才写新值。

这个坑已在本项目踩过三次（build_db_dict / build_graph_page / build_call_graph），
所以抽到这里统一，避免再犯第三次。
"""
from __future__ import annotations

from datetime import datetime


def now_str() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def sticky_generated_at(previous: dict | None, candidate: dict,
                        explicit: str | None = None) -> str:
    """返回应写入的 generated_at。

    previous  —— 既有产物里的数据（None 表示首次生成）
    candidate —— 本次要写入的数据（尚未设置 generated_at）
    explicit  —— 调用方强制指定（--generated-at），优先级最高
    """
    if explicit:
        return explicit
    if previous:
        strip = lambda d: {k: v for k, v in d.items() if k != "generated_at"}
        if strip(previous) == strip(candidate):
            return previous.get("generated_at") or now_str()
    return now_str()
