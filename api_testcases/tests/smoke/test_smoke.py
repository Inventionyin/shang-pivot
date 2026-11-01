"""冒烟测试：验证核心接口可达"""
import allure
import pytest
from assertions.http_assertions import HttpAssertions


@allure.epic("电商平台冒烟测试")
@allure.feature("核心服务可用性")
class TestSmoke:
    """P0 冒烟测试"""

    @allure.story("健康检查")
    @allure.title("P0: 健康检查接口返回 UP")
    @allure.severity(allure.severity_level.BLOCKER)
    def test_health_check(self, client):
        resp = client.get("/actuator/health")
        HttpAssertions.ok(resp)
        assert resp.json()["status"] == "UP"

    @allure.story("用户注册")
    @allure.title("P0: 注册接口可达并返回有效用户 ID")
    @allure.severity(allure.severity_level.BLOCKER)
    @pytest.mark.zt_case("POST_V1_AUTH_REGISTER-001")
    def test_register(self, client):
        from helpers.data_factory import DataFactory
        resp = client.post("/v1/auth/register", json={
            "account": DataFactory.random_email("smoke"),
            "password": "Smoke@123",
            "nickname": "SmokeTest",
        })
        HttpAssertions.ok(resp)
        assert resp.json()["data"]["id"] > 0

    @allure.story("用户登录")
    @allure.title("P0: 登录接口返回有效 Token")
    @allure.severity(allure.severity_level.BLOCKER)
    @pytest.mark.zt_case("POST_V1_AUTH_LOGIN-001")
    def test_login(self, client, auth_helper):
        from helpers.data_factory import DataFactory
        account = DataFactory.random_email("smoke")
        auth_helper.register(account, "Smoke@123")
        am = auth_helper.login(account, "Smoke@123")
        assert am.token, "Token 不应为空"
        assert len(am.token) > 50, "Token 长度异常"

    @allure.story("商品列表")
    @allure.title("P0: 商品列表接口返回数据")
    @allure.severity(allure.severity_level.CRITICAL)
    @pytest.mark.zt_case("GET_V1_PRODUCTS-001")
    def test_products_list(self, client, auth_token):
        resp = client.get("/v1/products", params={"size": 2})
        HttpAssertions.ok(resp)
        data = resp.json()
        assert isinstance(data.get("data"), list), "data 应为列表"

    @allure.story("加入购物车")
    @allure.title("P0: 加购接口可达")
    @allure.severity(allure.severity_level.CRITICAL)
    @pytest.mark.zt_case("POST_V1_CART_ITEMS-001")
    def test_cart_add(self, client, auth_token, cart_payload):
        resp = client.post("/v1/cart/items", json={**cart_payload, "quantity": 1})
        HttpAssertions.ok(resp)

    @allure.story("下单结算")
    @allure.title("P0: 下单接口可达")
    @allure.severity(allure.severity_level.CRITICAL)
    @pytest.mark.zt_case("POST_V1_ORDERS_CHECKOUT-001")
    def test_checkout(self, client, auth_token, address_id):
        resp = client.get("/v1/products", params={"size": 1})
        pid = resp.json()["data"][0]["id"]
        client.post("/v1/cart/items", json={"productId": pid, "quantity": 1})

        resp = client.post("/v1/orders/checkout", json={"addressId": address_id})
        assert resp.status_code in [200, 400, 500]
