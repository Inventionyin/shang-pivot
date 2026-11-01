"""商城个人中心相关页面：资料、设置、地址、收藏、足迹、售后、活动位。"""
from __future__ import annotations

from config.settings import settings
from core.base_page import BasePage


class MallAccountPage(BasePage):
    def __init__(self, page):
        super().__init__(page, settings.ui_mall_url)

    def open(self, path: str = "/me") -> None:
        super().open(path)

    def wait_title(self, title: str) -> None:
        self.page.locator("h1.page-title", has_text=title).first.wait_for(
            state="visible", timeout=max(self.timeout, 30000)
        )

    # ---------- 地址 ----------
    def open_addresses(self) -> None:
        self.open("/addresses")
        self.wait_title("收货地址")
        self.page.locator(".addr, .empty-hint").first.wait_for(
            state="visible", timeout=max(self.timeout, 30000)
        )

    def address_texts(self) -> list:
        return self.page.locator(".addr").all_inner_texts()

    def add_address(self, values: list) -> None:
        with self.step("点击新增地址"):
            self.page.get_by_role("button", name="新增", exact=True).click()
            self.page.locator(".van-popup .van-field").first.wait_for(
                state="visible", timeout=max(self.timeout, 30000)
            )
        with self.step("填写收货地址"):
            fields = self.page.locator(".van-popup .van-field input")
            assert fields.count() == len(values), f"地址表单字段数不符：{fields.count()}"
            for index, value in enumerate(values):
                fields.nth(index).fill(value)
        with self.step("保存收货地址"):
            self.page.get_by_role("button", name="保存", exact=True).click()
            self.page.get_by_text("已添加", exact=True).wait_for(
                state="visible", timeout=max(self.timeout, 30000)
            )
            self.page.wait_for_timeout(500)

    def delete_first_address(self) -> None:
        with self.step("删除收货地址"):
            # 右侧悬浮入口可能拦截点击，直接派发 DOM 事件
            self.page.locator(".addr .link.danger").first.dispatch_event("click")
            self.page.wait_for_timeout(700)

    # ---------- 收藏 ----------
    def open_favorites(self) -> None:
        self.open("/favorites")
        self.wait_title("我的收藏")
        self.page.locator(".fav-card, .empty-hint").first.wait_for(
            state="visible", timeout=max(self.timeout, 30000)
        )

    def favorite_titles(self) -> list:
        return [t.strip() for t in self.page.locator(".fav-card .title").all_inner_texts()]

    def remove_first_favorite(self) -> None:
        with self.step("点击取消收藏"):
            self.page.locator(".fav-card .unfav").first.dispatch_event("click")
            self.page.locator(".pop-title", has_text="取消收藏？").wait_for(
                state="visible", timeout=max(self.timeout, 30000)
            )
        with self.step("确认取消收藏"):
            self.page.locator(".pop-panel .btn-danger").click()
            self.page.wait_for_timeout(700)

    # ---------- 足迹 ----------
    def open_history(self) -> None:
        self.open("/history")
        self.wait_title("浏览足迹")
        self.page.locator(".product-card, .empty-hint").first.wait_for(
            state="visible", timeout=max(self.timeout, 30000)
        )

    def history_titles(self) -> list:
        return [
            t.strip()
            for t in self.page.locator(".product-card .product-card__title").all_inner_texts()
        ]

    def clear_history(self) -> None:
        with self.step("点击清空足迹"):
            self.page.get_by_role("button", name="清空", exact=True).click()
            dialog = self.page.locator(".van-dialog")
            dialog.get_by_text("确认清空浏览记录？").wait_for(
                state="visible", timeout=max(self.timeout, 30000)
            )
        with self.step("确认清空足迹"):
            dialog.get_by_role("button", name="确认").click()
            self.page.wait_for_timeout(800)

    # ---------- 售后 ----------
    def open_after_sales(self) -> None:
        self.open("/after-sales")
        self.wait_title("我的售后")
        self.page.locator(".card, .empty-hint").first.wait_for(
            state="visible", timeout=max(self.timeout, 30000)
        )
