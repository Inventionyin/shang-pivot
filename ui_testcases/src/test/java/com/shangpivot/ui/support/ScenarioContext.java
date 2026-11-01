package com.shangpivot.ui.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.shangpivot.ui.config.Env;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个 scenario 的共享状态（PicoContainer 按场景注入）。
 * 造出来的数据必须在这里登记，由 Hooks 在收尾时清理 —— 与 api_testcases 的"不留下无法清理的数据"同一要求。
 */
public class ScenarioContext {

    private ApiClient.Tokens mallTokens;
    private ApiClient.Tokens adminTokens;

    private String productTitle;
    private String createdOrderId;

    public ApiClient.Tokens mallTokens() {
        if (mallTokens == null) {
            mallTokens = ApiClient.mallLogin();
        }
        return mallTokens;
    }

    public ApiClient.Tokens adminTokens() {
        if (adminTokens == null) {
            adminTokens = ApiClient.adminLogin();
        }
        return adminTokens;
    }

    public String productTitle() {
        return productTitle;
    }

    public void productTitle(String value) {
        this.productTitle = value;
    }

    public void createdOrder(String orderId) {
        this.createdOrderId = orderId;
    }

    public String createdOrderId() {
        return createdOrderId;
    }

    /**
     * 准备购物车勾选态（结算携带哪些商品由它决定）：全部取消，只保留 keepTitle 匹配的行。
     * 用接口准备前置，被测行为（点结算后的提示与跳转）仍全部走界面。
     */
    public void prepareCartSelection(String keepTitle) {
        String token = mallTokens().accessToken();
        for (JsonNode item : ApiClient.cartItems(token)) {
            String title = item.path("title").asText();
            boolean keep = keepTitle != null && title.contains(keepTitle);
            if (!keep || !item.path("selected").asBoolean()) {
                ApiClient.setCartSelected(item.path("cartItemId").asText(), keep, token);
            }
        }
    }

    /** 清理本场景造的数据；返回问题清单（空 = 干净）。 */
    public List<String> cleanup() {
        List<String> problems = new ArrayList<>();
        if (productTitle != null) {
            problems.addAll(removeCartLinesOf(productTitle));
        }
        if (createdOrderId != null) {
            try {
                ApiClient.cancelOrder(mallTokens().accessToken(), createdOrderId);
            } catch (Exception e) {
                problems.add("订单 " + createdOrderId + " 取消失败：" + e.getMessage());
            }
        }
        return problems;
    }

    /** 按商品名清扫购物车行（加购场景造出来的数据只能这样定位）。 */
    private List<String> removeCartLinesOf(String title) {
        List<String> problems = new ArrayList<>();
        try {
            String token = mallTokens().accessToken();
            JsonNode items = ApiClient.get(Env.MALL_URL + "/api/cart", token).path("items");
            for (JsonNode item : items) {
                if (item.path("title").asText().contains(title)) {
                    ApiClient.delete(Env.MALL_URL + "/api/cart/items/" + item.path("cartItemId").asText(), token);
                }
            }
        } catch (Exception e) {
            problems.add("清理购物车行「" + title + "」失败：" + e.getMessage());
        }
        return problems;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("productTitle", productTitle);
        map.put("createdOrderId", createdOrderId);
        return map;
    }
}
