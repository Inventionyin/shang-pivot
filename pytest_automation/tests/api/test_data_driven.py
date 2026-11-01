"""参数化输入校验用例（数据驱动）。

数据在收集期从 YAML 读入，对应 pytest-learning 第 7、13、14 集的用法。
"""
from __future__ import annotations

from pathlib import Path

import pytest
import yaml

from core.assertions import error

pytestmark = [pytest.mark.api]

DATA_FILE = Path(__file__).resolve().parents[2] / "data" / "login_validation.yaml"


def _cases():
    with open(DATA_FILE, "r", encoding="utf-8") as f:
        return yaml.safe_load(f)["cases"]


@pytest.mark.P1
@pytest.mark.parametrize("case", _cases(), ids=lambda case: case["id"])
def test_login_input_validation(anonymous, case):
    resp = anonymous.post("/api/auth/login", json=case["body"])
    body = error(resp, code=case["expected_code"], http_status=case["expected_http"])
    assert case["msg_keyword"] in body["msg"]
