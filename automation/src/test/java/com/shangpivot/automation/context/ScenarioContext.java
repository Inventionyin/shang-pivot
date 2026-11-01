package com.shangpivot.automation.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.shangpivot.automation.api.AddressApi;
import com.shangpivot.automation.api.AdminApi;
import com.shangpivot.automation.api.AuthApi;
import com.shangpivot.automation.api.CartApi;
import com.shangpivot.automation.api.CatalogApi;
import com.shangpivot.automation.api.OrderApi;
import com.shangpivot.automation.api.PaymentApi;
import com.shangpivot.automation.config.TestConfig;
import com.shangpivot.automation.core.api.ApiResult;
import com.shangpivot.automation.core.api.RestClient;
import com.shangpivot.automation.core.data.TestData;
import com.shangpivot.automation.core.data.TestDataRegistry;
import org.assertj.core.api.Assertions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 场景级共享状态（PicoContainer 注入）。负责：唯一会员、令牌、领域 API、数据登记与清理。
 */
public final class ScenarioContext {

    private static final Logger log = LoggerFactory.getLogger(ScenarioContext.class);

    private final RestClient rest = new RestClient(TestConfig.API_BASE_URL);
    private final AuthApi auth = new AuthApi(rest);
    private final CatalogApi catalog = new CatalogApi(rest);
    private final TestDataRegistry registry = new TestDataRegistry();
    private final List<Runnable> cleanupHooks = new ArrayList<>();

    private String phone;
    private String customerToken;
    private String adminToken;

    private String productTitle;
    private String lastOrderId;
    private String lastOrderNo;
    private ApiResult lastResult;

    public ApiResult lastResult() {
        Assertions.assertThat(lastResult).as("最后一个接口响应为空").isNotNull();
        return lastResult;
    }

    public void lastResult(ApiResult result) {
        this.lastResult = result;
    }

    public RestClient rest() {
        return rest;
    }

    public AuthApi auth() {
        return auth;
    }

    public CatalogApi catalog() {
        return catalog;
    }

    public String phone() {
        if (phone == null) {
            phone = TestData.uniquePhone();
        }
        return phone;
    }

    public void phone(String value) {
        this.phone = value;
    }

    public String customerToken() {
        if (customerToken == null) {
            ApiResult result = auth.loginCustomer(phone(), TestConfig.DEMO_SMS_CODE).assertSuccess();
            customerToken = result.data().path("accessToken").asText();
            Assertions.assertThat(customerToken)
                    .as("登录响应未返回 accessToken；响应=%s", result.raw())
                    .isNotBlank();
        }
        return customerToken;
    }

    public void customerToken(String value) {
        this.customerToken = value;
    }

    public void adminToken(String value) {
        this.adminToken = value;
    }

    public String adminToken() {
        if (adminToken == null) {
            ApiResult result = auth.loginAdmin(TestConfig.ADMIN_USERNAME, TestConfig.ADMIN_PASSWORD).assertSuccess();
            adminToken = result.data().path("accessToken").asText();
            Assertions.assertThat(adminToken)
                    .as("管理员登录响应未返回 accessToken；响应=%s", result.raw())
                    .isNotBlank();
        }
        return adminToken;
    }

    public CartApi cart() {
        return new CartApi(rest, customerToken());
    }

    public AddressApi address() {
        return new AddressApi(rest, customerToken());
    }

    public OrderApi order() {
        return new OrderApi(rest, customerToken());
    }

    public PaymentApi payment() {
        return new PaymentApi(rest, customerToken());
    }

    public AdminApi admin() {
        return new AdminApi(rest, adminToken());
    }

    public TestDataRegistry registry() {
        return registry;
    }

    public void addCleanup(Runnable cleanup) {
        cleanupHooks.add(cleanup);
    }

    public String productTitle() {
        return productTitle;
    }

    public void productTitle(String value) {
        this.productTitle = value;
    }

    public String lastOrderId() {
        return lastOrderId;
    }

    public void lastOrderId(String value) {
        this.lastOrderId = value;
    }

    public String lastOrderNo() {
        return lastOrderNo;
    }

    public void lastOrderNo(String value) {
        this.lastOrderNo = value;
    }

    public String orderStatus(String orderId) {
        ApiResult detail = order().detail(orderId).assertSuccess();
        JsonNode order = detail.data().path("order");
        return order.has("status") ? order.path("status").asText() : detail.data().path("status").asText();
    }

    public String defaultAddressId() {
        ApiResult result = address().list().assertSuccess();
        for (JsonNode node : result.data()) {
            if (node.path("isDefault").asBoolean(false)) {
                return node.path("id").asText();
            }
        }
        Assertions.assertThat(result.data().size()).as("会员没有收货地址，无法下单").isPositive();
        return result.data().get(0).path("id").asText();
    }

    /** 场景结束清理：只处理本场景登记过的资源；返回无法清理的问题列表。 */
    public List<String> cleanup() {
        List<String> problems = new ArrayList<>();
        for (Runnable hook : cleanupHooks) {
            try {
                hook.run();
            } catch (Exception e) {
                problems.add("自定义清理异常: " + e.getMessage());
            }
        }
        if (customerToken == null) {
            return problems;
        }
        for (String orderId : registry.orderIds()) {
            try {
                ApiResult detail = order().detail(orderId);
                if (!detail.success()) {
                    continue;
                }
                JsonNode order = detail.data().path("order");
                String status = order.path("status").asText();
                if ("PENDING_PAY".equals(status)) {
                    ApiResult cancel = order().cancel(orderId);
                    if (!cancel.success() && cancel.code() != 40400) {
                        problems.add("取消订单 " + orderId + " 失败: " + cancel.message());
                    }
                }
            } catch (Exception e) {
                problems.add("清理订单 " + orderId + " 异常: " + e.getMessage());
            }
        }
        for (String addressId : registry.addressIds()) {
            try {
                ApiResult result = address().delete(addressId);
                if (!result.success() && result.code() != 40400) {
                    problems.add("删除地址 " + addressId + " 失败: " + result.message());
                }
            } catch (Exception e) {
                problems.add("删除地址 " + addressId + " 异常: " + e.getMessage());
            }
        }
        for (String cartItemId : registry.cartItemIds()) {
            try {
                ApiResult result = cart().remove(cartItemId);
                if (!result.success() && result.code() != 40400) {
                    problems.add("删除购物车项 " + cartItemId + " 失败: " + result.message());
                }
            } catch (Exception e) {
                problems.add("删除购物车项 " + cartItemId + " 异常: " + e.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            log.warn("[cleanup] {}", problems);
        }
        return problems;
    }
}
