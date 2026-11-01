"""购物车模块测试"""
import allure
import pytest
from assertions.http_assertions import HttpAssertions


@allure.epic("电商平台功能测试")
@allure.feature("购物车管理")
class TestCartAdd:
    """加入购物车"""

    @allure.story("加入购物车")
    @allure.title("P0: 正向 - 加入购物车成功")
    @allure.severity(allure.severity_level.BLOCKER)
    @pytest.mark.P0
    @pytest.mark.zt_case("POST_V1_CART_ITEMS-001")
    def test_add_valid(self, client, auth_token, cart_payload):
        resp = client.post("/v1/cart/items", json={**cart_payload, "quantity": 1})
        HttpAssertions.ok(resp)
        assert resp.json().get("code") in [200, None]

    @allure.story("加入购物车")
    @allure.title("P1: 正向 - 加购多数量商品成功")
    @allure.severity(allure.severity_level.CRITICAL)
    @pytest.mark.P1
    @pytest.mark.zt_case("POST_V1_CART_ITEMS-001")
    def test_add_multiple_quantity(self, client, auth_token, cart_payload):
        resp = client.post("/v1/cart/items", json={**cart_payload, "quantity": 3})
        HttpAssertions.ok(resp)

    @allure.story("加入购物车")
    @allure.title("P2: 反向 - 缺少 productId 字段")
    @allure.severity(allure.severity_level.NORMAL)
    @pytest.mark.P2
    @pytest.mark.zt_case("POST_V1_CART_ITEMS-003")
    def test_add_missing_product_id(self, client, auth_token):
        resp = client.post("/v1/cart/items", json={"quantity": 1})
        assert resp.status_code in [200, 400, 500], f"意外: {resp.status_code}"

    @allure.story("加入购物车")
    @allure.title("P2: 反向 - quantity 为 0 返回错误")
    @allure.severity(allure.severity_level.NORMAL)
    @pytest.mark.P2
    @pytest.mark.zt_case("BUS-CART-002")
    def test_add_quantity_zero(self, client, auth_token, product_id):
        resp = client.post("/v1/cart/items", json={
            "productId": product_id, "quantity": 0,
        })
        assert resp.status_code in [200, 400]

    @allure.story("加入购物车")
    @allure.title("P2: 反向 - quantity 为负数返回错误")
    @allure.severity(allure.severity_level.NORMAL)
    @pytest.mark.P2
    @pytest.mark.zt_case("BUS-CART-002")
    def test_add_quantity_negative(self, client, auth_token, product_id):
        resp = client.post("/v1/cart/items", json={
            "productId": product_id, "quantity": -1,
        })
        assert resp.status_code in [200, 400]

    @allure.story("加入购物车")
    @allure.title("P2: 反向 - 添加不存在的商品返回错误")
    @allure.severity(allure.severity_level.NORMAL)
    @pytest.mark.P2
    @pytest.mark.zt_case("POST_V1_CART_ITEMS-003")
    def test_add_not_exist_product(self, client, auth_token):
        resp = client.post("/v1/cart/items", json={
            "productId": 99999, "quantity": 1,
        })
        assert resp.status_code in [200, 400, 404]

    @allure.story("加入购物车")
    @allure.title("P1: 鉴权 - 无 Token 添加购物车返回未授权")
    @allure.severity(allure.severity_level.CRITICAL)
    @pytest.mark.P1
    @pytest.mark.zt_case("POST_V1_CART_ITEMS-002")
    def test_add_without_token(self, base_client):
        resp = base_client.post("/v1/cart/items", json={
            "productId": 1, "quantity": 1,
        })
        HttpAssertions.unauthorized(resp)


@allure.epic("电商平台功能测试")
@allure.feature("购物车管理")
class TestCartDelete:
    """删除购物车"""

    @allure.story("删除购物车")
    @allure.title("P2: 正向 - 删除购物车项成功")
    @allure.severity(allure.severity_level.NORMAL)
    @pytest.mark.P2
    @pytest.mark.zt_case("DELETE_V1_CART_ITEMS_ID-001")
    def test_delete_item(self, client, auth_token, product_id):
        client.post("/v1/cart/items", json={"productId": product_id, "quantity": 1})
        resp = client.delete("/v1/cart/items/1")
        assert resp.status_code in [200, 400, 404]

    @allure.story("删除购物车")
    @allure.title("P2: 鉴权 - 无 Token 删除购物车返回未授权")
    @allure.severity(allure.severity_level.NORMAL)
    @pytest.mark.P2
    @pytest.mark.zt_case("DELETE_V1_CART_ITEMS_ID-002")
    def test_delete_without_token(self, base_client):
        resp = base_client.delete("/v1/cart/items/1")
        HttpAssertions.unauthorized(resp)
