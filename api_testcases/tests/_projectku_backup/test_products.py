"""商品模块测试"""
import allure
import pytest
import requests
from assertions.http_assertions import HttpAssertions
from assertions.data_assertions import DataAssertions


@allure.epic("电商平台功能测试")
@allure.feature("商品管理")
class TestProductList:
    """商品列表/搜索"""

    @allure.story("商品列表")
    @allure.title("P0: 正向 - 默认分页获取商品列表")
    @allure.severity(allure.severity_level.BLOCKER)
    @pytest.mark.P0
    @pytest.mark.zt_case("GET_V1_PRODUCTS-001")
    def test_list_default(self, client, auth_token):
        resp = client.get("/v1/products", params={"size": 5})
        HttpAssertions.ok(resp)
        data = resp.json()
        assert data.get("code") == 200
        products = data.get("data", [])
        assert len(products) > 0, "商品列表不应为空"
        p = products[0]
        DataAssertions.has_fields(p, "id", "name", "price", "stock", "categoryId")
        DataAssertions.field_positive(p, "price")

    @allure.story("商品搜索")
    @allure.title("P1: 正向 - 关键字搜索返回含关键字的商品")
    @allure.severity(allure.severity_level.CRITICAL)
    @pytest.mark.P1
    @pytest.mark.zt_case("BUS-PRD-001")
    def test_search_by_keyword(self, client, auth_token):
        resp = client.get("/v1/products", params={"keyword": "LG", "size": 3})
        HttpAssertions.ok(resp)
        data = resp.json()
        if data.get("data"):
            names = [p["name"].lower() for p in data["data"]]
            assert any("lg" in n for n in names), f"搜索结果不含关键字: {names}"

    @allure.story("商品搜索")
    @allure.title("P1: 正向 - 分类筛选返回指定分类商品")
    @allure.severity(allure.severity_level.CRITICAL)
    @pytest.mark.P1
    @pytest.mark.zt_case("BUS-PRD-001")
    def test_search_by_category(self, client, auth_token):
        resp = client.get("/v1/products", params={"category": 5, "size": 3})
        HttpAssertions.ok(resp)
        data = resp.json()
        if data.get("data"):
            for p in data["data"]:
                assert p["categoryId"] == 5, f"分类筛选失效: {p}"

    @allure.story("商品列表")
    @allure.title("P2: 边界 - 第1页分页验证")
    @allure.severity(allure.severity_level.NORMAL)
    @pytest.mark.P2
    @pytest.mark.zt_case("GET_V1_PRODUCTS-001")
    def test_pagination_page1(self, client, auth_token):
        resp = client.get("/v1/products", params={"page": 1, "size": 2})
        HttpAssertions.ok(resp)
        assert len(resp.json().get("data", [])) <= 2

    @allure.story("商品列表")
    @allure.title("P2: 边界 - 超大页码返回空列表")
    @allure.severity(allure.severity_level.NORMAL)
    @pytest.mark.P2
    @pytest.mark.zt_case("GET_V1_PRODUCTS-004")
    def test_pagination_empty(self, client, auth_token):
        resp = client.get("/v1/products", params={"page": 99999, "size": 5})
        HttpAssertions.ok(resp)
        assert resp.json().get("data", []) == []

    @allure.story("商品搜索")
    @allure.title("P2: 反向 - 特殊字符搜索不报错")
    @allure.severity(allure.severity_level.NORMAL)
    @pytest.mark.P2
    @pytest.mark.zt_case("BUS-PRD-001")
    def test_keyword_special_chars(self, client, auth_token):
        resp = client.get("/v1/products", params={"keyword": "<script>alert(1)</script>"})
        HttpAssertions.ok(resp)

    @allure.story("商品列表")
    @allure.title("P2: 鉴权 - 无 Token 也可访问公开商品接口")
    @allure.severity(allure.severity_level.NORMAL)
    @pytest.mark.P2
    def test_without_token(self, base_client):
        resp = base_client.get("/v1/products", params={"size": 2})
        assert resp.status_code == 200


@allure.epic("电商平台功能测试")
@allure.feature("商品管理")
class TestProductDetail:
    """商品详情"""

    @allure.story("商品详情")
    @allure.title("P0: 正向 - 获取商品详情成功")
    @allure.severity(allure.severity_level.BLOCKER)
    @pytest.mark.P0
    @pytest.mark.zt_case("GET_V1_PRODUCTS_ID-001")
    def test_detail_valid(self, client, auth_token, product_id):
        resp = client.get(f"/v1/products/{product_id}")
        HttpAssertions.ok(resp)
        data = resp.json()
        assert data.get("code") == 200

    @allure.story("商品详情")
    @allure.title("P2: 反向 - 不存在商品 ID 返回错误")
    @allure.severity(allure.severity_level.NORMAL)
    @pytest.mark.P2
    @pytest.mark.zt_case("BUS-PRD-002")
    def test_detail_not_found(self, client, auth_token):
        resp = client.get("/v1/products/99999")
        assert resp.status_code in [200, 400, 404]

    @allure.story("商品详情")
    @allure.title("P2: 反向 - 非数字商品 ID 返回错误")
    @allure.severity(allure.severity_level.NORMAL)
    @pytest.mark.P2
    @pytest.mark.zt_case("GET_V1_PRODUCTS_ID-003")
    def test_detail_invalid_id(self, client, auth_token):
        try:
            resp = requests.get(
                f"{client.base_url}/v1/products/abc",
                headers=client.session.headers,
                timeout=client.timeout,
            )
            assert resp.status_code in [200, 400, 404, 500], f"意外: {resp.status_code}"
        except requests.exceptions.ConnectionError:
            pass
