"""数据结构断言"""
from typing import Any


class DataAssertions:
    @staticmethod
    def has_fields(data: dict, *fields: str):
        for field in fields:
            assert field in data, f"缺少字段: {field}"

    @staticmethod
    def field_type(data: dict, field: str, expected_type: type):
        assert isinstance(data[field], expected_type), (
            f"字段 {field} 类型错误: 预期 {expected_type.__name__}, "
            f"实际 {type(data[field]).__name__}"
        )

    @staticmethod
    def field_positive(data: dict, field: str):
        assert data[field] > 0, f"字段 {field} 应大于 0，实际: {data[field]}"

    @staticmethod
    def field_not_empty(data: dict, field: str):
        val = data[field]
        assert val, f"字段 {field} 不应为空: {val}"

    @staticmethod
    def field_list(data: dict, field: str, min_len: int = 0):
        val = data.get(field, [])
        assert isinstance(val, list), f"字段 {field} 应为列表，实际: {type(val).__name__}"
        if min_len > 0:
            assert len(val) >= min_len, f"字段 {field} 长度应 >= {min_len}，实际: {len(val)}"

    @staticmethod
    def is_list(value: Any, name: str = "data"):
        assert isinstance(value, list), f"{name} 应为列表，实际: {type(value).__name__}"

    @staticmethod
    def is_dict(value: Any, name: str = "data"):
        assert isinstance(value, dict), f"{name} 应为字典，实际: {type(value).__name__}"