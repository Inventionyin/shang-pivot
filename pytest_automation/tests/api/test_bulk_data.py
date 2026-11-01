"""大数据量接口测试：大分页、深翻页、批量写入与翻页一致性。

关注三类风险：
- 大 size 请求是否被服务端正确限制/处理（OOM、慢查询、5xx）；
- 深页（page 超范围）是否稳定返回空集而不是报错；
- 批量写入后列表/购物车是否保持分页一致（无重复、无丢行、金额正确）。
响应时间断言是「慢即失败」的软 perf 门禁（阈值放宽到 3–5s，避免环境抖动误报）。
"""
from __future__ import annotations

import time

import pytest

from core.assertions import ok
from helpers import db
from helpers.auth_helper import add_to_cart, create_address, submit_order
from helpers.data_factory import address_payload, order_payload

pytestmark = [pytest.mark.api, pytest.mark.bulk]

PAGE_BUDGET_MS = 3000
BULK_BUDGET_MS = 8000


@pytest.fixture(scope="module")
def db_ready():
    if not db.available():
        pytest.skip("数据库不可达：大数据量用例需要从库中取在售 SKU 做批量构造")
    return True


def _timed(client, method: str, path: str, **kwargs):
    started = time.perf_counter()
    resp = client.request(method, path, **kwargs)
    elapsed_ms = (time.perf_counter() - started) * 1000
    return resp, elapsed_ms


@pytest.mark.P1
def test_product_list_large_page_size(anonymous):
    """商品列表一次取 100 条：不截断报错、不超时、分页字段自洽。"""
    resp, elapsed_ms = _timed(
        anonymous, "GET", "/api/products", params={"page": 1, "size": 100}
    )
    data = ok(resp)
    assert elapsed_ms < PAGE_BUDGET_MS, f"size=100 用时 {elapsed_ms:.0f}ms，超过 {PAGE_BUDGET_MS}ms"
    assert isinstance(data["records"], list) and len(data["records"]) <= 100
    assert int(data["total"]) >= len(data["records"])
    assert data["records"], "演示数据应至少有 1 个在售 SPU"
    for row in data["records"]:
        assert row.get("spuId") and row.get("name") is not None


@pytest.mark.P1
def test_product_list_page_beyond_last(anonymous):
    """深翻页（page=9999）：返回空集，HTTP 200，不得 5xx。"""
    resp, _ = _timed(
        anonymous, "GET", "/api/products", params={"page": 9999, "size": 20}
    )
    data = ok(resp)
    assert data["records"] == [], f"超范围页仍返回 {len(data['records'])} 条"


@pytest.mark.P1
def test_search_large_page(anonymous):
    """搜索大分页：取首页商品名前缀作关键词，size=50 响应稳定且有界。"""
    first_page = ok(anonymous.get("/api/products", params={"page": 1, "size": 1}))
    assert first_page["records"], "演示数据缺少在售商品，无法构造搜索关键词"
    keyword = str(first_page["records"][0]["name"])[:2]

    resp, elapsed_ms = _timed(
        anonymous, "GET", "/api/search", params={"keyword": keyword, "page": 1, "size": 50}
    )
    data = ok(resp)
    assert elapsed_ms < PAGE_BUDGET_MS, f"搜索 size=50 用时 {elapsed_ms:.0f}ms，超过预算"
    assert len(data["records"]) <= 50
    assert int(data["total"]) >= len(data["records"])


@pytest.mark.P1
def test_order_list_pagination_consistency(customer, db_ready):
    """批量下单后翻页：页间无重复、每页不超 size、total 覆盖已建订单。"""
    sku = db.pick_on_sale_sku(min_available=8)
    assert sku, "在售库存不足，无法构造批量订单"
    sku_id = str(sku["sku_id"])
    address_id = create_address(customer.client, address_payload(customer.phone))

    created = []
    for _ in range(6):
        item = add_to_cart(customer.client, sku_id, 1)
        order = submit_order(
            customer.client,
            order_payload(address_id=address_id, cart_item_ids=[str(item["cartItemId"])]),
        )
        created.append(str(order["orderId"]))

    first, t1 = _timed(customer.client, "GET", "/api/orders", params={"page": 1, "size": 3})
    second, t2 = _timed(customer.client, "GET", "/api/orders", params={"page": 2, "size": 3})
    page1, page2 = ok(first), ok(second)

    assert max(t1, t2) < PAGE_BUDGET_MS, f"订单翻页耗时 {t1:.0f}/{t2:.0f}ms 超预算"
    ids1 = [str(row["id"]) for row in page1["records"]]
    ids2 = [str(row["id"]) for row in page2["records"]]
    assert len(ids1) <= 3 and len(ids2) <= 3
    assert not set(ids1) & set(ids2), f"第 1/2 页出现重复订单：{set(ids1) & set(ids2)}"
    assert int(page1["total"]) >= 6, f"total={page1['total']} 未覆盖已建 6 单"
    assert set(created) <= set(ids1) | set(ids2), "新建订单未出现在前两页（排序或分页丢行）"


@pytest.mark.P1
@pytest.mark.serial
def test_cart_bulk_add_skus(customer, db_ready):
    """批量加购 15 个不同 SKU：行数正确、选中金额等于逐行求和。"""
    skus = db.query(
        """
        SELECT s.sku_id, s.physical_stock - s.locked_stock AS available_stock, k.price
          FROM commerce.stock s
          JOIN commerce.sku k ON k.id = s.sku_id AND k.deleted = FALSE
          JOIN commerce.spu p ON p.id = k.spu_id AND p.deleted = FALSE
         WHERE s.deleted = FALSE AND p.status = 'ON' AND k.status = 'ENABLED'
           AND s.physical_stock - s.locked_stock >= 1
         ORDER BY s.sku_id
         LIMIT 15
        """
    )
    assert len(skus) == 15, f"可售 SKU 不足 15 个（实际 {len(skus)}），无法构造批量场景"

    started = time.perf_counter()
    for sku in skus:
        add_to_cart(customer.client, str(sku["sku_id"]), 1)
    elapsed_ms = (time.perf_counter() - started) * 1000

    cart = ok(customer.client.get("/api/cart"))
    assert elapsed_ms < BULK_BUDGET_MS, f"批量加购 15 次用时 {elapsed_ms:.0f}ms，超过预算"
    assert len(cart["items"]) == 15, f"购物车行数 {len(cart['items'])} != 15"
    expected = sum((sku["price"] for sku in skus), start=0)
    assert float(cart["selectedAmount"]) == pytest.approx(float(expected), abs=0.01)
    assert all(int(row["quantity"]) == 1 for row in cart["items"])


@pytest.mark.P2
def test_admin_order_list_large_page(admin):
    """管理端订单列表大分页：size=100 稳定返回，耗时可接受。"""
    resp, elapsed_ms = _timed(
        admin, "GET", "/admin/orders", params={"page": 1, "size": 100}
    )
    data = ok(resp)
    assert elapsed_ms < PAGE_BUDGET_MS, f"管理端 size=100 用时 {elapsed_ms:.0f}ms 超预算"
    assert len(data["records"]) <= 100
    assert int(data["total"]) >= len(data["records"])
