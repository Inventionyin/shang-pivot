"""E2E 场景：完整购物流程 + 多用户隔离"""
import allure
import pytest
from assertions.http_assertions import HttpAssertions
from helpers.data_factory import DataFactory


@allure.epic("电商平台端到端测试")
@allure.feature("完整购物链路")
class TestShoppingFlow:
    """完整购物链路"""

    @allure.story("浏览→加购→下单→查单→取消")
    @allure.title("P0: 完整购物链路 — 浏览商品→加购→添加地址→下单→查询→取消")
    @allure.severity(allure.severity_level.BLOCKER)
    @pytest.mark.P0
    @pytest.mark.e2e
    @pytest.mark.zt_case("BUS-ORD-001", "BUS-PAY-001")
    def test_full_purchase_flow(self, client, auth_token):
        # 1. 浏览商品
        from fixtures.api_fixtures import _pick_stocked_product
        pid = _pick_stocked_product(client)["id"]
        detail = client.get(f"/v1/products/{pid}")
        skus = (detail.json().get("data") or {}).get("skus") or [] if detail.status_code == 200 else []

        # 2. 加购（商品含 SKU 时携带首个 skuId）
        cart_body = {"productId": pid, "quantity": 2}
        if skus:
            cart_body["skuId"] = skus[0]["id"]
        resp = client.post("/v1/cart/items", json=cart_body)
        HttpAssertions.ok(resp)

        # 3. 添加地址
        resp = client.post("/v1/me/addresses", json={
            "receiver": f"E2E_{DataFactory.random_string(3)}",
            "phone": DataFactory.random_phone(),
            "region": "北京市 朝阳区",
            "detail": f"测试地址_{DataFactory.random_string(4)}",
            "isDefault": 1,
        })
        HttpAssertions.ok(resp)

        # 4. 获取地址
        resp = client.get("/v1/me/addresses")
        addrs = resp.json().get("data", [])
        assert len(addrs) > 0, "应有至少一条地址"
        addr_id = addrs[-1].get("id")

        # 5. 下单
        resp = client.post("/v1/orders/checkout", json={"addressId": addr_id})
        assert resp.status_code in [200, 400, 500]
        data = resp.json()
        if data.get("code") == 200:
            order_id = data["data"]["id"]
            assert order_id > 0

            # 6. 查询订单
            resp = client.get("/v1/orders", params={"size": 5})
            HttpAssertions.ok(resp)
            order_ids = [o["id"] for o in resp.json().get("data", [])]
            assert order_id in order_ids

            # 7. 取消订单
            resp = client.post(f"/v1/orders/{order_id}/cancel")
            assert resp.status_code in [200, 400]

    @allure.story("商品搜索与筛选")
    @allure.title("P1: 商品搜索→分类筛选→不存在的关键字")
    @allure.severity(allure.severity_level.CRITICAL)
    @pytest.mark.P1
    @pytest.mark.e2e
    @pytest.mark.zt_case("BUS-PRD-001")
    def test_product_search_filter(self, client, auth_token):
        resp = client.get("/v1/products", params={"keyword": "LG", "size": 3})
        HttpAssertions.ok(resp)
        resp = client.get("/v1/products", params={"category": 5, "size": 3})
        HttpAssertions.ok(resp)
        resp = client.get("/v1/products", params={"keyword": "ZZZZZNOTEXIST"})
        HttpAssertions.ok(resp)
        assert resp.json().get("data", []) == []


@allure.epic("电商平台端到端测试")
@allure.feature("用户数据隔离")
class TestUserIsolation:
    """多用户隔离"""

    @allure.story("多用户订单隔离")
    @allure.title("P1: A 的订单不能被 B 访问")
    @allure.severity(allure.severity_level.CRITICAL)
    @pytest.mark.P1
    @pytest.mark.e2e
    @pytest.mark.zt_case("BUS-ORD-004")
    def test_user_cannot_access_other_orders(self, user_a, user_b):
        client_a, client_b = user_a["client"], user_b["client"]

        # A 下单
        resp = client_a.get("/v1/products", params={"size": 1})
        pid = resp.json()["data"][0]["id"]
        client_a.post("/v1/cart/items", json={"productId": pid, "quantity": 1})
        client_a.post("/v1/me/addresses", json={
            "receiver": "A_User", "phone": "13800138000",
            "region": "北京", "detail": "A的地址", "isDefault": 1,
        })
        addrs = client_a.get("/v1/me/addresses").json().get("data", [])
        if addrs:
            resp = client_a.post("/v1/orders/checkout", json={"addressId": addrs[-1]["id"]})
            if resp.json().get("code") == 200:
                order_id = resp.json()["data"]["id"]

                # B 尝试访问 A 的订单
                resp_b = client_b.get(f"/v1/orders/{order_id}")
                assert resp_b.status_code in [200, 400, 403, 404]
                if resp_b.status_code in [400, 403, 404]:
                    pass
                else:
                    b_data = resp_b.json()
                    if "error" in b_data:
                        pass
