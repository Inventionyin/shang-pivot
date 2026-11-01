"""商品浏览接口用例：首页、分类、分页、排序、详情、搜索。"""
from __future__ import annotations

import pytest

from core.assertions import assert_pagination, assert_trace_id, error, ok

pytestmark = [pytest.mark.api]


class TestHome:
    @pytest.mark.P0
    @pytest.mark.smoke
    def test_home_feed_and_slots(self, anonymous):
        data = ok(anonymous.get("/api/home", params={"feedSize": 3}))
        assert isinstance(data.get("feed"), list) and data["feed"]
        assert isinstance(data.get("slots"), list) and data["slots"]
        first = data["feed"][0]
        for key in ("spuId", "name", "price"):
            assert key in first, f"首页 feed 缺少 {key}"

    @pytest.mark.P1
    def test_site_brand(self, anonymous):
        data = ok(anonymous.get("/api/site/brand"))
        assert data.get("name")
        assert data.get("slogan") is not None

    @pytest.mark.P1
    def test_categories(self, anonymous):
        data = ok(anonymous.get("/api/categories"))
        assert isinstance(data, list) and data
        assert all(str(item.get("id")) for item in data[:5])


class TestProducts:
    @pytest.mark.P0
    @pytest.mark.smoke
    def test_products_pagination(self, anonymous):
        data = ok(anonymous.get("/api/products", params={"page": 1, "size": 5}))
        assert_pagination(data, page=1, size=5)
        assert int(data["total"]) > 0

    @pytest.mark.P1
    @pytest.mark.parametrize(
        "sort,descending",
        [("price_asc", False), ("price_desc", True)],
        ids=["price_asc", "price_desc"],
    )
    def test_price_sort(self, anonymous, sort, descending):
        data = ok(anonymous.get("/api/products", params={"sort": sort, "size": 8}))
        prices = [float(row["price"]) for row in data["records"] if row.get("price") is not None]
        assert len(prices) >= 2
        expected = sorted(prices, reverse=descending)
        assert prices == expected, f"{sort} 排序不符：{prices}"

    @pytest.mark.P0
    def test_product_detail(self, anonymous):
        data = ok(anonymous.get("/api/products/101"))
        assert str(data["spu"]["id"]) == "101"
        assert isinstance(data["spu"].get("pics"), list)
        assert isinstance(data["skus"], list) and data["skus"]
        assert isinstance(data.get("favorited"), bool)
        for sku in data["skus"]:
            assert isinstance(sku["skuId"], str), "Long 字段 skuId 应为字符串"
            assert int(sku["availableStock"]) >= 0

    @pytest.mark.P1
    def test_product_not_found(self, anonymous):
        resp = anonymous.get("/api/products/999999")
        body = error(resp, code=40410)
        assert "商品" in body["msg"]

    @pytest.mark.P2
    def test_out_of_range_page_is_stable(self, anonymous):
        """负数分页不应 5xx：允许空页或回落到第一页。"""
        resp = anonymous.get("/api/products", params={"page": -1, "size": 10})
        body = ok(resp)
        assert_pagination(body)

    @pytest.mark.P2
    def test_huge_size_is_stable(self, anonymous):
        resp = anonymous.get("/api/products", params={"page": 1, "size": 100000})
        ok(resp)
        assert resp.status_code == 200


class TestSearch:
    @pytest.mark.P1
    @pytest.mark.parametrize("keyword", ["iPhone", "MacBook", "戴森"])
    def test_search_hit(self, anonymous, keyword):
        data = ok(anonymous.get("/api/search", params={"q": keyword, "size": 10}))
        assert_pagination(data)
        assert int(data["total"]) > 0, f"关键字 {keyword} 应命中商品"
        names = " ".join(str(row.get("name", "")) for row in data["records"])
        assert keyword.lower().split()[0] in names.lower()

    @pytest.mark.P1
    def test_search_no_result(self, anonymous):
        data = ok(anonymous.get("/api/search", params={"q": "不存在的商品xyz", "size": 10}))
        assert int(data["total"]) == 0
        assert data["records"] == []

    @pytest.mark.P2
    def test_search_empty_keyword(self, anonymous):
        data = ok(anonymous.get("/api/search", params={"q": "", "size": 5}))
        assert_pagination(data)

    @pytest.mark.security
    @pytest.mark.P2
    def test_search_sql_injection_is_safe(self, anonymous):
        resp = anonymous.get("/api/search", params={"q": "' OR 1=1 --", "size": 10})
        body = ok(resp)
        assert body["records"] == [], "注入串不应被当作 SQL 条件"
        assert_trace_id(resp)
