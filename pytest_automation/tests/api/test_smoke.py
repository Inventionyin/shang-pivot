"""冒烟子集：用最少的用例确认环境与主链路可用。"""
from __future__ import annotations

import pytest

from core.assertions import ok

pytestmark = [pytest.mark.api, pytest.mark.smoke, pytest.mark.P0]


def test_storefront_is_reachable(anonymous):
    data = ok(anonymous.get("/api/products", params={"size": 1}))
    assert data["records"]


def test_customer_can_login(customer):
    data = ok(customer.client.get("/api/me"))
    assert data["status"] == "NORMAL"


def test_admin_can_login(admin):
    data = ok(admin.get("/admin/auth/me"))
    assert data["username"]
