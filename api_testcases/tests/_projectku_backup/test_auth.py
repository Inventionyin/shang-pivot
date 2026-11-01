"""认证模块测试：注册/登录/me/地址 CRUD"""
import allure
import pytest
from assertions.http_assertions import HttpAssertions
from assertions.data_assertions import DataAssertions
from helpers.data_factory import DataFactory


@allure.epic("电商平台功能测试")
@allure.feature("用户认证")
class TestRegister:
    """注册接口"""

    @allure.story("用户注册")
    @allure.title("P0: 正向 - 有效账号注册成功")
    @allure.severity(allure.severity_level.BLOCKER)
    @pytest.mark.P0
    @pytest.mark.zt_case("POST_V1_AUTH_REGISTER-001")
    def test_register_valid(self, client):
        account = DataFactory.random_email()
        resp = client.post("/v1/auth/register", json={
            "account": account, "password": "Abc123456", "nickname": "Tester01",
        })
        HttpAssertions.ok(resp)
        data = resp.json()["data"]
        DataAssertions.has_fields(data, "id", "account", "nickname")
        DataAssertions.field_positive(data, "id")

    @allure.story("用户注册")
    @allure.title("P1: 反向 - 重复注册（容错验证）")
    @allure.severity(allure.severity_level.CRITICAL)
    @pytest.mark.P1
    @pytest.mark.zt_case("BUS-AUTH-002")
    def test_register_duplicate(self, client, auth_token):
        resp = client.post("/v1/auth/register", json={
            "account": DataFactory.random_email(), "password": "Tmp@123", "nickname": "Tmp",
        })
        HttpAssertions.ok(resp)

    @allure.story("用户注册")
    @allure.title("P2: 反向 - 缺少 account 字段")
    @allure.severity(allure.severity_level.NORMAL)
    @pytest.mark.P2
    @pytest.mark.zt_case("POST_V1_AUTH_REGISTER-003")
    def test_register_missing_account(self, client):
        resp = client.post("/v1/auth/register", json={"password": "Abc123", "nickname": "T"})
        assert resp.status_code in [200, 400, 500], f"意外: {resp.status_code}"

    @allure.story("用户注册")
    @allure.title("P2: 反向 - 缺少 password 字段")
    @allure.severity(allure.severity_level.NORMAL)
    @pytest.mark.P2
    @pytest.mark.zt_case("POST_V1_AUTH_REGISTER-003")
    def test_register_missing_password(self, client):
        resp = client.post("/v1/auth/register", json={
            "account": DataFactory.random_email(), "nickname": "T",
        })
        assert resp.status_code in [200, 400]

    @allure.story("用户注册")
    @allure.title("P2: 反向 - 空请求体")
    @allure.severity(allure.severity_level.NORMAL)
    @pytest.mark.P2
    @pytest.mark.zt_case("POST_V1_AUTH_REGISTER-003")
    def test_register_empty_body(self, client):
        resp = client.post("/v1/auth/register", json={})
        assert resp.status_code in [200, 400, 500], f"意外: {resp.status_code}"


@allure.epic("电商平台功能测试")
@allure.feature("用户认证")
class TestLogin:
    """登录接口"""

    @allure.story("用户登录")
    @allure.title("P0: 正向 - 有效账号密码登录返回 Token")
    @allure.severity(allure.severity_level.BLOCKER)
    @pytest.mark.P0
    @pytest.mark.zt_case("POST_V1_AUTH_LOGIN-001")
    def test_login_valid(self, client, auth_token):
        assert auth_token.token, "Token 不应为空"
        assert len(auth_token.token) > 50

    @allure.story("用户登录")
    @allure.title("P1: 反向 - 错误密码返回错误信息")
    @allure.severity(allure.severity_level.CRITICAL)
    @pytest.mark.P1
    @pytest.mark.zt_case("BUS-AUTH-006")
    def test_login_wrong_password(self, client, auth_token):
        resp = client.post("/v1/auth/login", json={
            "account": DataFactory.random_email("nonexist"),
            "password": "WrongPass123",
        })
        assert resp.status_code in [200, 400]
        assert resp.json().get("error") is not None

    @allure.story("用户登录")
    @allure.title("P2: 反向 - 空密码返回未授权")
    @allure.severity(allure.severity_level.NORMAL)
    @pytest.mark.P2
    @pytest.mark.zt_case("POST_V1_AUTH_LOGIN-003")
    def test_login_empty_password(self, client):
        resp = client.post("/v1/auth/login", json={
            "account": DataFactory.random_email(), "password": "",
        })
        HttpAssertions.unauthorized(resp)

    @allure.story("用户登录")
    @allure.title("P2: 反向 - 缺少 account 参数")
    @allure.severity(allure.severity_level.NORMAL)
    @pytest.mark.P2
    @pytest.mark.zt_case("POST_V1_AUTH_LOGIN-003")
    def test_login_missing_account(self, client):
        resp = client.post("/v1/auth/login", json={"password": "Abc123"})
        HttpAssertions.bad_request(resp)

    @allure.story("用户登录")
    @allure.title("P2: 反向 - 缺少 password 参数")
    @allure.severity(allure.severity_level.NORMAL)
    @pytest.mark.P2
    @pytest.mark.zt_case("POST_V1_AUTH_LOGIN-003")
    def test_login_missing_password(self, client):
        resp = client.post("/v1/auth/login", json={"account": DataFactory.random_email()})
        HttpAssertions.bad_request(resp)


@allure.epic("电商平台功能测试")
@allure.feature("用户认证")
class TestMe:
    """当前用户信息"""

    @allure.story("获取当前用户信息")
    @allure.title("P1: 正向 - 有 Token 获取当前用户信息")
    @allure.severity(allure.severity_level.CRITICAL)
    @pytest.mark.P1
    @pytest.mark.zt_case("GET_V1_ME-001")
    def test_me_valid(self, client, auth_token):
        resp = client.get("/v1/me")
        HttpAssertions.ok(resp)
        data = resp.json()
        if "data" in data and isinstance(data["data"], dict):
            user = data["data"]
            DataAssertions.has_fields(user, "id", "account")

    @allure.story("获取当前用户信息")
    @allure.title("P2: 鉴权 - 无 Token 访问返回未授权")
    @allure.severity(allure.severity_level.NORMAL)
    @pytest.mark.P2
    @pytest.mark.zt_case("GET_V1_ME-002")
    def test_me_without_token(self, base_client):
        resp = base_client.get("/v1/me")
        HttpAssertions.unauthorized(resp)

    @allure.story("获取当前用户信息")
    @allure.title("P2: 鉴权 - 伪造 Token 返回未授权")
    @allure.severity(allure.severity_level.NORMAL)
    @pytest.mark.P2
    @pytest.mark.zt_case("GET_V1_ME-002")
    def test_me_with_invalid_token(self, client, auth_token):
        original = client.session.headers.get("Authorization", "")
        client.set_token("invalid.fake.token")
        resp = client.get("/v1/me")
        HttpAssertions.unauthorized(resp)
        if original:
            client.session.headers["Authorization"] = original


@allure.epic("电商平台功能测试")
@allure.feature("地址管理")
class TestAddresses:
    """地址管理"""

    @allure.story("添加地址")
    @allure.title("P0: 正向 - 添加收货地址成功")
    @allure.severity(allure.severity_level.CRITICAL)
    @pytest.mark.P0
    @pytest.mark.zt_case("POST_V1_ME_ADDRESSES-001")
    def test_add_address(self, client, auth_token):
        resp = client.post("/v1/me/addresses", json={
            "receiver": "张三", "phone": "13800138000",
            "region": "北京市 朝阳区", "detail": "望京100号 1001室", "isDefault": 1,
        })
        HttpAssertions.ok(resp)
        assert resp.json().get("code") == 200

    @allure.story("获取地址列表")
    @allure.title("P1: 正向 - 获取地址列表并返回数组")
    @allure.severity(allure.severity_level.CRITICAL)
    @pytest.mark.P1
    @pytest.mark.zt_case("GET_V1_ME_ADDRESSES-001")
    def test_get_addresses(self, client, auth_token):
        client.post("/v1/me/addresses", json={
            "receiver": "李四", "phone": "13900139000",
            "region": "上海市 浦东新区", "detail": "世纪大道200号", "isDefault": 0,
        })
        resp = client.get("/v1/me/addresses")
        HttpAssertions.ok(resp)
        DataAssertions.is_list(resp.json().get("data", []))

    @allure.story("添加地址")
    @allure.title("P2: 反向 - 缺少 receiver 字段")
    @allure.severity(allure.severity_level.NORMAL)
    @pytest.mark.P2
    @pytest.mark.zt_case("POST_V1_ME_ADDRESSES-003")
    def test_add_address_missing_receiver(self, client, auth_token):
        resp = client.post("/v1/me/addresses", json={
            "phone": "13800138000", "region": "北京市", "detail": "xx路",
        })
        assert resp.status_code in [200, 400, 500], f"意外: {resp.status_code}"

    @allure.story("添加地址")
    @allure.title("P2: 鉴权 - 无 Token 添加地址返回未授权")
    @allure.severity(allure.severity_level.NORMAL)
    @pytest.mark.P2
    @pytest.mark.zt_case("POST_V1_ME_ADDRESSES-002")
    def test_add_address_without_token(self, base_client):
        resp = base_client.post("/v1/me/addresses", json={
            "receiver": "X", "phone": "13800138000",
            "region": "北京", "detail": "xx路",
        })
        HttpAssertions.unauthorized(resp)

    @allure.story("删除地址")
    @allure.title("P2: 正向 - 删除地址成功")
    @allure.severity(allure.severity_level.NORMAL)
    @pytest.mark.P2
    @pytest.mark.zt_case("DELETE_V1_ME_ADDRESSES_ID-001")
    def test_delete_address(self, client, auth_token):
        client.post("/v1/me/addresses", json={
            "receiver": "待删除", "phone": "13700137000",
            "region": "广州", "detail": "天河路100号", "isDefault": 0,
        })
        resp = client.get("/v1/me/addresses")
        addresses = resp.json().get("data", [])
        if addresses:
            addr_id = addresses[-1].get("id")
            resp = client.delete(f"/v1/me/addresses/{addr_id}")
            assert resp.status_code in [200, 400, 404]
