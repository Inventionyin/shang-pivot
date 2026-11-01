"""失败/阻断留痕演示：默认跳过，仅用于验证证据链完整性。

运行方式（单独产物目录，不污染正式报告）::

    DEMO_FAILURE=1 python -m pytest -m demo \
        --alluredir=artifacts/allure-failure-demo --clean-alluredir

覆盖两种失败形态：
- failed：业务断言不成立（带 traceId，可关联后端日志）；
- broken：用例执行异常（KeyError），验证堆栈与「失败依据」附件。
"""
from __future__ import annotations

import os

import pytest

from core.assertions import payload

pytestmark = [pytest.mark.demo]

_DISABLED = os.getenv("DEMO_FAILURE") != "1"


@pytest.mark.skipif(_DISABLED, reason="演示用例：DEMO_FAILURE=1 时运行")
def test_demo_business_assertion_failure(anonymous):
    """故意让业务断言失败：期望 code=0，实际返回 40410。"""
    resp = anonymous.get("/api/products/999999")
    body = payload(resp)
    assert body["code"] == 0, (
        f"演示失败：期望业务码 0，实际 {body['code']}（{body.get('msg')}）"
    )


@pytest.mark.skipif(_DISABLED, reason="演示用例：DEMO_FAILURE=1 时运行")
def test_demo_broken_exception(anonymous):
    """故意抛异常：验证阻断（broken）状态的堆栈与证据附件。"""
    resp = anonymous.get("/api/products", params={"page": 1, "size": 1})
    data = resp.json()["data"]
    data["records"][0]["fieldThatDoesNotExist"]
