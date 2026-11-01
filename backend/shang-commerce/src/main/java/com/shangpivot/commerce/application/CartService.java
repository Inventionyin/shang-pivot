package com.shangpivot.commerce.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.commerce.entity.*;
import com.shangpivot.commerce.mapper.*;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.security.SecurityContexts;
import com.shangpivot.identity.api.CustomerQueryApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
public class CartService {

    private final CartMapper cartMapper;
    private final CartItemMapper cartItemMapper;
    private final SkuMapper skuMapper;
    private final SpuMapper spuMapper;
    private final StockMapper stockMapper;
    private final CustomerQueryApi customerQueryApi;

    public Map<String, Object> getCart() {
        Long customerId = requireActiveCustomer();
        Cart cart = ensureCart(customerId);
        List<CartItem> items = cartItemMapper.selectList(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getCartId, cart.getId())
                .orderByDesc(CartItem::getId));
        List<Map<String, Object>> vos = new ArrayList<>();
        BigDecimal selectedAmount = BigDecimal.ZERO;
        for (CartItem item : items) {
            Map<String, Object> vo = toItemVo(item);
            vos.add(vo);
            if (Boolean.TRUE.equals(vo.get("selected")) && Boolean.TRUE.equals(vo.get("valid"))) {
                BigDecimal price = (BigDecimal) vo.get("price");
                selectedAmount = selectedAmount.add(price.multiply(BigDecimal.valueOf(item.getQuantity())));
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("items", vos);
        result.put("selectedAmount", selectedAmount);
        result.put("available", true);
        return result;
    }

    @Transactional
    public Map<String, Object> addItem(Long skuId, int quantity) {
        Long customerId = requireActiveCustomer();
        return addItemFor(customerId, skuId, quantity);
    }

    /** customerId 显式入参版本（V3.0 智能客服加购工具：进程内调用无 HTTP 安全上下文）。 */
    @Transactional
    public Map<String, Object> addItemFor(Long customerId, Long skuId, int quantity) {
        if (quantity < 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "数量须 ≥ 1");
        }
        if (!customerQueryApi.isActive(customerId)) {
            throw new BizException(ErrorCode.CUSTOMER_FROZEN);
        }
        Sku sku = requireOnSaleSku(skuId);
        int available = availableStock(skuId);
        Cart cart = ensureCart(customerId);
        CartItem existing = cartItemMapper.selectOne(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getCartId, cart.getId())
                .eq(CartItem::getSkuId, skuId));
        int nextQty = existing == null ? quantity : existing.getQuantity() + quantity;
        if (nextQty > available) {
            throw new BizException(ErrorCode.STOCK_INSUFFICIENT);
        }
        if (existing == null) {
            existing = new CartItem();
            existing.setCartId(cart.getId());
            existing.setSkuId(skuId);
            existing.setQuantity(nextQty);
            existing.setSelected(true);
            cartItemMapper.insert(existing);
        } else {
            existing.setQuantity(nextQty);
            cartItemMapper.updateById(existing);
        }
        Map<String, Object> m = new HashMap<>();
        m.put("cartItemId", existing.getId());
        m.put("quantity", existing.getQuantity());
        m.put("selected", existing.getSelected());
        return m;
    }

    @Transactional
    public void updateItem(Long itemId, Integer quantity, Boolean selected) {
        Long customerId = requireActiveCustomer();
        CartItem item = requireOwnedItem(itemId, customerId);
        if (quantity != null) {
            if (quantity < 1) {
                throw new BizException(ErrorCode.BAD_REQUEST, "数量须 ≥ 1");
            }
            if (quantity > availableStock(item.getSkuId())) {
                throw new BizException(ErrorCode.STOCK_INSUFFICIENT);
            }
            item.setQuantity(quantity);
        }
        if (selected != null) {
            item.setSelected(selected);
        }
        cartItemMapper.updateById(item);
    }

    @Transactional
    public void removeItem(Long itemId) {
        Long customerId = requireActiveCustomer();
        CartItem item = requireOwnedItem(itemId, customerId);
        cartItemMapper.deleteById(item.getId());
    }

    @Transactional
    public void clearInvalid() {
        Long customerId = requireActiveCustomer();
        Cart cart = ensureCart(customerId);
        List<CartItem> items = cartItemMapper.selectList(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getCartId, cart.getId()));
        for (CartItem item : items) {
            Map<String, Object> vo = toItemVo(item);
            if (!Boolean.TRUE.equals(vo.get("valid"))) {
                cartItemMapper.deleteById(item.getId());
            }
        }
    }

    Cart ensureCart(Long customerId) {
        Cart cart = cartMapper.selectOne(new LambdaQueryWrapper<Cart>().eq(Cart::getCustomerId, customerId));
        if (cart == null) {
            cart = new Cart();
            cart.setCustomerId(customerId);
            cartMapper.insert(cart);
        }
        return cart;
    }

    private Long requireActiveCustomer() {
        Long id = SecurityContexts.requireUserId();
        if (!customerQueryApi.isActive(id)) {
            throw new BizException(ErrorCode.CUSTOMER_FROZEN);
        }
        return id;
    }

    private CartItem requireOwnedItem(Long itemId, Long customerId) {
        Cart cart = ensureCart(customerId);
        CartItem item = cartItemMapper.selectById(itemId);
        if (item == null || !cart.getId().equals(item.getCartId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "购物车项不存在");
        }
        return item;
    }

    private Sku requireOnSaleSku(Long skuId) {
        Sku sku = skuMapper.selectById(skuId);
        if (sku == null || !"ENABLED".equals(sku.getStatus())) {
            throw new BizException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        Spu spu = spuMapper.selectById(sku.getSpuId());
        if (spu == null || !"ON".equals(spu.getStatus())) {
            throw new BizException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        return sku;
    }

    private int availableStock(Long skuId) {
        Stock stock = stockMapper.selectOne(new LambdaQueryWrapper<Stock>().eq(Stock::getSkuId, skuId).last("LIMIT 1"));
        if (stock == null) {
            return 0;
        }
        return Math.max(0, (stock.getPhysicalStock() == null ? 0 : stock.getPhysicalStock())
                - (stock.getLockedStock() == null ? 0 : stock.getLockedStock()));
    }

    private Map<String, Object> toItemVo(CartItem item) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", item.getId());
        m.put("cartItemId", item.getId());
        m.put("skuId", item.getSkuId());
        m.put("quantity", item.getQuantity());
        m.put("selected", item.getSelected());
        Sku sku = skuMapper.selectById(item.getSkuId());
        if (sku == null) {
            m.put("valid", false);
            m.put("available", false);
            m.put("invalidReason", "SKU 不存在");
            m.put("price", BigDecimal.ZERO);
            return m;
        }
        Spu spu = spuMapper.selectById(sku.getSpuId());
        boolean on = spu != null && "ON".equals(spu.getStatus()) && "ENABLED".equals(sku.getStatus());
        int available = availableStock(sku.getId());
        boolean enough = item.getQuantity() <= available;
        m.put("spuId", sku.getSpuId());
        m.put("title", sku.getTitle());
        m.put("pic", sku.getPic());
        m.put("specsText", sku.getSpecs() == null ? "" : sku.getSpecs().toString());
        m.put("price", sku.getPrice());
        m.put("valid", on && enough);
        m.put("available", on && enough);
        if (!on) {
            m.put("invalidReason", "商品已下架");
        } else if (!enough) {
            m.put("invalidReason", "库存不足");
        }
        return m;
    }
}
