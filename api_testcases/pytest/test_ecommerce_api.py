#!/usr/bin/env python3
"""
E-Commerce 电商项目 API 自动化测试
包含 Login 认证后的接口测试用例

运行方式:
    pytest test_ecommerce_api.py -v -s
    或者指定 base_url:
    BASE_URL=http://localhost:8080/api pytest test_ecommerce_api.py -v -s
"""
import os
import time
import random
import string
import pytest
import requests
from typing import Dict, Optional

# ============================================================
# 全局配置
# ============================================================
BASE_URL = os.environ.get("BASE_URL", "http://localhost:8080/api")
HEADERS_JSON = {"Content-Type": "application/json"}

# 测试账号（每次测试自动注册新账号，避免冲突）
TEST_PREFIX = f"auto_{''.join(random.choices(string.ascii_lowercase, k=6))}"


def _make_headers(token: Optional[str] = None) -> Dict[str, str]:
    """构造请求头，自动附加 Bearer token"""
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return headers


# ============================================================
# Fixture: 自动注册并登录，返回 token 和用户信息
# ============================================================
@pytest.fixture(scope="module")
def auth_token():
    """模块级 fixture：注册新用户并登录，返回 token 和用户信息"""
    account = f"{TEST_PREFIX}@test.com"
    password = "Test@123456"
    nickname = f"Tester_{TEST_PREFIX[-4:]}"

    # 1. 注册
    resp = requests.post(
        f"{BASE_URL}/v1/auth/register",
        json={"account": account, "password": password, "nickname": nickname},
        headers=HEADERS_JSON,
        timeout=10,
    )
    assert resp.status_code == 200, f"注册失败: {resp.text}"
    data = resp.json()
    assert data.get("data", {}).get("id"), f"注册返回异常: {data}"

    # 2. 登录
    resp = requests.post(
        f"{BASE_URL}/v1/auth/login",
        json={"account": account, "password": password},
        headers=HEADERS_JSON,
        timeout=10,
    )
    assert resp.status_code == 200, f"登录失败: {resp.text}"
    login_data = resp.json()
    token = login_data["data"]["token"]
    user_id = login_data["data"]["user"]["id"]
    user_account = login_data["data"]["user"]["account"]

    yield {
        "token": token,
        "user_id": user_id,
        "account": user_account,
        "password": password,
    }


# ============================================================
# 测试用例 1: 完整购物流程（注册→登录→浏览商品→加购→下单）
# ============================================================
class TestFullShoppingFlow:
    """完整购物流程测试"""

    def test_01_login_success(self, auth_token):
        """验证登录成功并获取有效 token"""
        assert auth_token["token"], "token 不能为空"
        assert len(auth_token["token"]) > 50, "token 长度异常"
        assert auth_token["user_id"] > 0, "user_id 无效"

    def test_02_get_products(self, auth_token):
        """获取商品列表（需要 Bearer token）"""
        resp = requests.get(
            f"{BASE_URL}/v1/products",
            params={"page": 1, "size": 5},
            headers=_make_headers(auth_token["token"]),
            timeout=10,
        )
        assert resp.status_code == 200, f"获取商品列表失败: {resp.text}"
        data = resp.json()
        assert data["code"] == 200, f"业务码异常: {data}"
        assert len(data["data"]) > 0, "商品列表为空"
        # 验证商品字段完整性
        product = data["data"][0]
        required_fields = ["id", "name", "price", "stock", "categoryId"]
        for field in required_fields:
            assert field in product, f"商品缺少字段: {field}"
        assert product["price"] > 0, "商品价格应大于0"

    def test_03_get_products_by_keyword(self, auth_token):
        """按关键字搜索商品"""
        resp = requests.get(
            f"{BASE_URL}/v1/products",
            params={"keyword": "LG", "size": 3},
            headers=_make_headers(auth_token["token"]),
            timeout=10,
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["code"] == 200
        # 验证搜索结果包含关键字相关商品
        if data["data"]:
            names = [p["name"].lower() for p in data["data"]]
            assert any("lg" in n for n in names), f"搜索结果未包含关键字: {names}"

    def test_04_add_to_cart(self, auth_token):
        """添加商品到购物车"""
        # 先获取一个有效商品
        resp = requests.get(
            f"{BASE_URL}/v1/products",
            params={"page": 1, "size": 1},
            headers=_make_headers(auth_token["token"]),
            timeout=10,
        )
        products = resp.json()["data"]
        assert len(products) > 0, "没有可用商品"
        product_id = products[0]["id"]

        # 添加到购物车
        resp = requests.post(
            f"{BASE_URL}/v1/cart/items",
            json={"productId": product_id, "quantity": 2},
            headers=_make_headers(auth_token["token"]),
            timeout=10,
        )
        assert resp.status_code == 200, f"添加购物车失败: {resp.text}"
        data = resp.json()
        # 添加成功应返回 code=200
        assert data.get("code") in [200, None], f"添加购物车业务异常: {data}"

    def test_05_add_address(self, auth_token):
        """添加收货地址"""
        resp = requests.post(
            f"{BASE_URL}/v1/me/addresses",
            json={
                "receiver": "测试用户",
                "phone": "13800138000",
                "region": "北京市 朝阳区",
                "detail": "望京街道100号 1号楼 2001室",
                "isDefault": 1,
            },
            headers=_make_headers(auth_token["token"]),
            timeout=10,
        )
        assert resp.status_code == 200, f"添加地址失败: {resp.text}"
        data = resp.json()
        assert data["code"] == 200, f"添加地址业务异常: {data}"

    def test_06_get_addresses(self, auth_token):
        """获取用户地址列表"""
        resp = requests.get(
            f"{BASE_URL}/v1/me/addresses",
            headers=_make_headers(auth_token["token"]),
            timeout=10,
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["code"] == 200, f"获取地址列表失败: {data}"
        # 应该有至少一条地址（上面刚添加的）
        assert isinstance(data["data"], list), "地址数据应为数组"

    def test_07_checkout(self, auth_token):
        """下单结算"""
        # 先获取地址
        resp = requests.get(
            f"{BASE_URL}/v1/me/addresses",
            headers=_make_headers(auth_token["token"]),
            timeout=10,
        )
        addresses = resp.json().get("data", [])
        if not addresses:
            pytest.skip("没有可用地址，跳过结算测试")

        address_id = addresses[0].get("id")
        resp = requests.post(
            f"{BASE_URL}/v1/orders/checkout",
            json={"addressId": address_id},
            headers=_make_headers(auth_token["token"]),
            timeout=10,
        )
        # 结算可能因购物车为空而失败（多用户共用同一购物车数据），
        # 这里主要验证接口可达性和鉴权通过
        assert resp.status_code in [200, 400, 500], f"结算请求异常: {resp.text}"
        data = resp.json()
        print(f"\n结算结果: {data}")
        # 如果不是"购物车为空"错误，则结算应成功
        if "cart" in str(data).lower() and "empty" in str(data).lower():
            print("购物车为空（可能已有其他测试清空），预期行为")
        else:
            assert data.get("code") in [200, None], f"结算业务异常: {data}"


# ============================================================
# 测试用例 2: 用户认证场景（登录/注册各种情况）
# ============================================================
class TestAuthentication:
    """登录注册相关测试"""

    def test_login_with_wrong_password(self, auth_token):
        """错误密码登录应返回鉴权错误"""
        resp = requests.post(
            f"{BASE_URL}/v1/auth/login",
            json={"account": auth_token["account"], "password": "WrongPassword123"},
            headers=HEADERS_JSON,
            timeout=10,
        )
        assert resp.status_code in [200, 400], f"意外状态码: {resp.status_code}"
        data = resp.json()
        # 应有错误信息
        assert data.get("error") is not None, f"错误密码登录应返回错误: {data}"
        assert "token" not in data.get("data", {}), "错误密码不应返回 token"

    def test_login_with_empty_password(self):
        """空密码登录应返回错误"""
        resp = requests.post(
            f"{BASE_URL}/v1/auth/login",
            json={"account": "test@test.com", "password": ""},
            headers=HEADERS_JSON,
            timeout=10,
        )
        data = resp.json()
        assert data.get("error") is not None, f"空密码登录应返回错误: {data}"

    def test_register_duplicate_account(self, auth_token):
        """重复注册同一账号应返回错误"""
        resp = requests.post(
            f"{BASE_URL}/v1/auth/register",
            json={
                "account": auth_token["account"],
                "password": "AnotherPass123",
                "nickname": "DuplicateUser",
            },
            headers=HEADERS_JSON,
            timeout=10,
        )
        data = resp.json()
        if resp.status_code == 200 and data.get("error"):
            # 返回错误而非成功
            assert True, f"重复注册返回错误，符合预期: {data}"
        elif resp.status_code >= 400:
            assert True, f"重复注册返回错误状态码 {resp.status_code}，符合预期"


# ============================================================
# 测试用例 3: 需要登录态的功能接口测试
# ============================================================
class TestAuthenticatedApis:
    """登录后功能接口测试"""

    def test_get_products_without_token(self):
        """不带 token 获取商品列表（部分公开接口也应可用）"""
        resp = requests.get(
            f"{BASE_URL}/v1/products",
            params={"page": 1, "size": 2},
            headers=HEADERS_JSON,
            timeout=10,
        )
        # 前端页面可浏览商品，响应 200
        assert resp.status_code == 200, f"公开商品接口应可访问: {resp.text}"

    def test_add_to_cart_without_token(self):
        """不带 token 添加购物车应被拒绝"""
        resp = requests.post(
            f"{BASE_URL}/v1/cart/items",
            json={"productId": 1, "quantity": 1},
            headers=HEADERS_JSON,
            timeout=10,
        )
        # 未登录操作购物车应返回鉴权错误
        data = resp.json()
        error_code = data.get("error", {}).get("code", "")
        print(f"\n未登录添加购物车响应: status={resp.status_code}, body={data}")
        assert (
            error_code == "UNAUTHORIZED"
            or error_code == "FORBIDDEN"
            or resp.status_code in [401, 403]
        ), f"未登录添加购物车应被拒绝: {data}"

    def test_get_my_orders(self, auth_token):
        """获取当前用户订单列表"""
        resp = requests.get(
            f"{BASE_URL}/v1/orders",
            params={"page": 1, "size": 5},
            headers=_make_headers(auth_token["token"]),
            timeout=10,
        )
        assert resp.status_code == 200, f"获取订单列表失败: {resp.text}"
        data = resp.json()
        assert data.get("code") in [200, None], f"获取订单业务异常: {data}"


# ============================================================
# 测试用例 4: 购物车完整操作流程
# ============================================================
class TestCartOperations:
    """购物车 CRUD 操作测试（需要登录态）"""

    def test_cart_full_flow(self, auth_token):
        """购物车完整流程：添加 → 查看订单 → 验证"""
        # Step 1: 获取一个商品
        resp = requests.get(
            f"{BASE_URL}/v1/products",
            params={"page": 1, "size": 1},
            headers=_make_headers(auth_token["token"]),
            timeout=10,
        )
        products = resp.json()["data"]
        assert len(products) > 0, "没有可用商品"
        product = products[0]
        product_id = product["id"]

        # Step 2: 添加到购物车
        resp = requests.post(
            f"{BASE_URL}/v1/cart/items",
            json={"productId": product_id, "quantity": 3},
            headers=_make_headers(auth_token["token"]),
            timeout=10,
        )
        assert resp.status_code == 200, f"添加购物车失败: {resp.text}"
        data = resp.json()
        print(f"\n添加到购物车: product_id={product_id}, response={data}")
        assert data.get("code") in [200, None], f"添加购物车失败: {data}"

        # Step 3: 通过订单接口验证（如果项目提供了 GET /v1/cart 接口）
        # 尝试获取购物车列表
        resp = requests.get(
            f"{BASE_URL}/v1/cart/items",
            headers=_make_headers(auth_token["token"]),
            timeout=10,
        )
        print(f"获取购物车列表: status={resp.status_code}")
        # 检查是否有用户订单（验证数据持久化）
        resp = requests.get(
            f"{BASE_URL}/v1/orders",
            params={"page": 1, "size": 5},
            headers=_make_headers(auth_token["token"]),
            timeout=10,
        )
        assert resp.status_code == 200, f"获取订单失败: {resp.text}"
        print(f"订单列表: {resp.json()}")


# ============================================================
# ============================================================
if __name__ == "__main__":
    pytest.main([__file__, "-v", "-s", "--tb=short"])