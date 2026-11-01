package com.shangpivot.automation.core.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 场景级数据登记表。只记录“本场景创建了什么”，清理时精确定位，不按标题模糊匹配。
 */
public final class TestDataRegistry {

    private final List<String> cartItemIds = new ArrayList<>();
    private final List<String> addressIds = new ArrayList<>();
    private final List<String> orderIds = new ArrayList<>();

    public void registerCartItem(String cartItemId) {
        if (cartItemId != null && !cartItemId.isBlank()) {
            cartItemIds.add(cartItemId);
        }
    }

    public void registerAddress(String addressId) {
        if (addressId != null && !addressId.isBlank()) {
            addressIds.add(addressId);
        }
    }

    public void registerOrder(String orderId) {
        if (orderId != null && !orderId.isBlank()) {
            orderIds.add(orderId);
        }
    }

    public List<String> cartItemIds() {
        return Collections.unmodifiableList(cartItemIds);
    }

    public List<String> addressIds() {
        return Collections.unmodifiableList(addressIds);
    }

    public List<String> orderIds() {
        return Collections.unmodifiableList(orderIds);
    }

    public boolean isEmpty() {
        return cartItemIds.isEmpty() && addressIds.isEmpty() && orderIds.isEmpty();
    }
}
