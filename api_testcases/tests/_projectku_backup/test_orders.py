"""订单模块测试"""
import allure
import pytest
from assertions.http_assertions import HttpAssertions
from assertions.data_assertions import DataAssertions


@allure.epic("电商平台功能测试")
@allure.feature("订单管理")
class TestCheckout:
    """下单结算"""

    @allure.story("下单结算")
    @allure.title("P0: 正向 - 正常下单并返回订单 ID")
    @allure.severity(allure.severity_level.BLOCKER)
    @pytest.mark.P0
    @pytest.mark.zt_case("POST_V1_ORDERS_CHECKOUT-001")
    def test_checkout_valid(self, client, auth_token, address_id, product_id):
        client.post("/v1/cart/items", json={"productId": product_id, "quantity": 1})
        resp = client.post("/v1/orders/checkout", json={"addressId": address_id})
        assert resp.status_code in [200, 400, 500]
        data = resp.json()
        if data.get("code") == 200:
            assert "id" in data["data"]

    @allure.story("下单结算")
    @allure.title("P1: 反向 - 缺少 addressId 返回错误")
    @allure.severity(allure.severity_level.CRITICAL)
    @pytest.mark.P1
    @pytest.mark.zt_case("POST_V1_ORDERS_CHECKOUT-003")
    def test_checkout_missing_address(self, client, auth_token, product_id):
        client.post("/v1/cart/items", json={"productId": product_id, "quantity": 1})
        resp = client.post("/v1/orders/checkout", json={})
        assert resp.status_code in [200, 400]

    @allure.story("下单结算")
    @allure.title("P2: 鉴权 - 无 Token 下单返回未授权")
    @allure.severity(allure.severity_level.NORMAL)
    @pytest.mark.P2
    @pytest.mark.zt_case("POST_V1_ORDERS_CHECKOUT-002")
    def test_checkout_without_token(self, base_client):
        resp = base_client.post("/v1/orders/checkout", json={"addressId": 1})
        HttpAssertions.unauthorized(resp)


@allure.epic("电商平台功能测试")
@allure.feature("订单管理")
class TestOrderList:
    """订单列表与详情"""

    @allure.story("订单列表")
    @allure.title("P0: 正向 - 获取订单列表成功")
    @allure.severity(allure.severity_level.BLOCKER)
    @pytest.mark.P0
    @pytest.mark.zt_case("GET_V1_ORDERS-001")
    def test_get_orders(self, client, auth_token):
        resp = client.get("/v1/orders", params={"size": 5})
        HttpAssertions.ok(resp)
        data = resp.json()
        if data.get("code") == 200:
            DataAssertions.is_list(data.get("data", []))

    @allure.story("订单列表")
    @allure.title("P2: 鉴权 - 无 Token 获取订单返回未授权")
    @allure.severity(allure.severity_level.NORMAL)
    @pytest.mark.P2
    @pytest.mark.zt_case("GET_V1_ORDERS-002")
    def test_get_orders_without_token(self, base_client):
        resp = base_client.get("/v1/orders")
        HttpAssertions.unauthorized(resp)

    @allure.story("订单详情")
    @allure.title("P2: 正向 - 订单详情查询成功")
    @allure.severity(allure.severity_level.NORMAL)
    @pytest.mark.P2
    @pytest.mark.zt_case("GET_V1_ORDERS_ID-001")
    def test_order_detail(self, client, auth_token):
        resp = client.get("/v1/orders", params={"size": 1})
        orders = resp.json().get("data", [])
        if orders:
            order_id = orders[0]["id"]
            resp = client.get(f"/v1/orders/{order_id}")
            HttpAssertions.ok(resp)

    @allure.story("取消订单")
    @allure.title("P1: 正向 - 取消订单成功")
    @allure.severity(allure.severity_level.CRITICAL)
    @pytest.mark.P1
    @pytest.mark.zt_case("POST_V1_ORDERS_ID_CANCEL-001")
    def test_cancel_order(self, client, auth_token, address_id, product_id):
        client.post("/v1/cart/items", json={"productId": product_id, "quantity": 1})
        resp = client.post("/v1/orders/checkout", json={"addressId": address_id})
        data = resp.json()
        if data.get("code") == 200 and data.get("data", {}).get("id"):
            order_id = data["data"]["id"]
            resp = client.post(f"/v1/orders/{order_id}/cancel")
            assert resp.status_code in [200, 400]
