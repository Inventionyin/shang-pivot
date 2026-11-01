"""商城浏览 UI 用例：首页、搜索、分类。"""
from __future__ import annotations

import pytest

from pages.mall_home_page import MallHomePage

pytestmark = [pytest.mark.ui]


class TestMallBrowse:
    @pytest.mark.P0
    @pytest.mark.smoke
    def test_home_loads_products(self, mall_page):
        home = MallHomePage(mall_page)
        home.open("/")
        home.wait_products()
        assert home.product_links().count() > 0
        assert "尚品汇" in home.text()
        assert "热门精选" in home.text()

    @pytest.mark.P1
    def test_search_shows_matching_products(self, mall_page):
        home = MallHomePage(mall_page)
        home.search("iPhone")
        home.wait_products()
        assert "iPhone" in home.text()
        assert home.search_cards().count() > 0

    @pytest.mark.P2
    def test_category_page_loads(self, mall_page):
        home = MallHomePage(mall_page)
        home.open("/category")
        mall_page.locator("h1.page-title").filter(has_text="分类").wait_for(
            state="visible", timeout=15000
        )
        assert mall_page.locator("a[href^='/product/']").count() > 0
        assert "综合" in home.text() and "销量" in home.text()

    @pytest.mark.P1
    def test_search_no_result_shows_empty_state(self, mall_page):
        home = MallHomePage(mall_page)
        home.search("不存在的商品xyz")
        mall_page.locator(".empty-hint", has_text="没有找到相关商品").wait_for(
            state="visible", timeout=15000
        )
        assert "为你推荐" in home.text()
