"""商城商品详情 UI 用例。"""
from __future__ import annotations

import pytest

from pages.mall_product_page import MallProductPage

pytestmark = [pytest.mark.ui]


class TestMallProduct:
    @pytest.mark.P1
    def test_product_detail_shows_skus(self, logged_in_mall_page):
        product = MallProductPage(logged_in_mall_page)
        product.open("101")
        text = logged_in_mall_page.locator("body").inner_text()
        assert "iPhone 15 Pro" in text
        assert logged_in_mall_page.locator(".chip").count() > 0
        assert product.favorite_text() in ("收藏", "已收藏")

    @pytest.mark.P1
    def test_favorite_toggle(self, logged_in_mall_page):
        product = MallProductPage(logged_in_mall_page)
        product.open("101")
        initial = product.favorite_text()

        product.toggle_favorite("已收藏")
        assert product.is_favorited()

        product.toggle_favorite("收藏")
        assert product.favorite_text() == initial
