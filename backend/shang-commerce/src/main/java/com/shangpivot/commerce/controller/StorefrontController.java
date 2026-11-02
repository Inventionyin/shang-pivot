package com.shangpivot.commerce.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shangpivot.commerce.application.*;
import com.shangpivot.commerce.entity.Order;
import com.shangpivot.commerce.entity.OrderItem;
import com.shangpivot.commerce.entity.Review;
import com.shangpivot.commerce.entity.Shipment;
import com.shangpivot.commerce.mapper.OrderItemMapper;
import com.shangpivot.commerce.mapper.OrderMapper;
import com.shangpivot.commerce.mapper.ReviewMapper;
import com.shangpivot.commerce.mapper.ShipmentMapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.result.PageResult;
import com.shangpivot.common.result.Result;
import com.shangpivot.common.security.SecurityContexts;
import com.shangpivot.identity.api.CustomerQueryApi;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Tag(name = "C端商城", description = "浏览、购物车、地址、订单、支付")
public class StorefrontController {

    private final CatalogReadService catalogReadService;
    private final SiteBrandService siteBrandService;
    private final CartService cartService;
    private final AddressService addressService;
    private final OrderSubmitService orderSubmitService;
    private final PaymentSandboxService paymentSandboxService;
    private final FavoriteBrowseService favoriteBrowseService;
    private final AfterSaleService afterSaleService;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ShipmentMapper shipmentMapper;
    private final ReviewMapper reviewMapper;
    private final CustomerQueryApi customerQueryApi;

    @GetMapping("/api/site/brand")
    public Result<Map<String, Object>> brand() {
        return Result.ok(siteBrandService.get());
    }

    @GetMapping("/api/home")
    public Result<Map<String, Object>> home(@RequestParam(defaultValue = "20") int feedSize) {
        return Result.ok(catalogReadService.home(feedSize));
    }

    @GetMapping("/api/categories")
    public Result<List<Map<String, Object>>> categories() {
        return Result.ok(catalogReadService.categories());
    }

    @GetMapping("/api/products")
    public Result<PageResult<Map<String, Object>>> products(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "comp") String sort) {
        return Result.ok(catalogReadService.productPage(categoryId, page, size, sort));
    }

    @GetMapping("/api/products/{spuId}")
    public Result<Map<String, Object>> productDetail(@PathVariable Long spuId) {
        Map<String, Object> detail = catalogReadService.detail(spuId);
        Long customerId = SecurityContexts.optionalUserId();
        if (customerId != null) {
            favoriteBrowseService.touchHistory(customerId, spuId);
            detail.put("favorited", favoriteBrowseService.isFavorite(spuId));
        } else {
            detail.put("favorited", false);
        }
        return Result.ok(detail);
    }

    @GetMapping("/api/search")
    public Result<PageResult<Map<String, Object>>> search(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(catalogReadService.search(q, page, size));
    }

    @GetMapping("/api/cart")
    public Result<Map<String, Object>> cart() {
        return Result.ok(cartService.getCart());
    }

    @PostMapping("/api/cart/items")
    public Result<Map<String, Object>> addCart(@RequestBody Map<String, Object> body) {
        Object skuRaw = body.get("skuId");
        Object qtyRaw = body.get("quantity");
        if (skuRaw == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择商品规格");
        }
        if (qtyRaw == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写购买数量");
        }
        Long skuId;
        int qty;
        try {
            skuId = Long.valueOf(skuRaw.toString());
            qty = Integer.parseInt(qtyRaw.toString());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "skuId/quantity 格式不正确");
        }
        return Result.ok(cartService.addItem(skuId, qty));
    }

    @PutMapping("/api/cart/items/{id}")
    public Result<Void> updateCart(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Integer qty = body.get("quantity") == null ? null : Integer.parseInt(body.get("quantity").toString());
        Boolean selected = body.get("selected") == null ? null : Boolean.valueOf(body.get("selected").toString());
        cartService.updateItem(id, qty, selected);
        return Result.ok();
    }

    @DeleteMapping("/api/cart/items/{id}")
    public Result<Void> removeCart(@PathVariable Long id) {
        cartService.removeItem(id);
        return Result.ok();
    }

    @PostMapping("/api/cart/clear-invalid")
    public Result<Void> clearInvalid() {
        cartService.clearInvalid();
        return Result.ok();
    }

    @GetMapping("/api/addresses")
    public Result<List<?>> addresses() {
        return Result.ok(addressService.list());
    }

    @PostMapping("/api/addresses")
    public Result<Long> createAddress(@RequestBody AddressService.AddressReq req) {
        return Result.ok(addressService.create(req));
    }

    @PutMapping("/api/addresses/{id}")
    public Result<Void> updateAddress(@PathVariable Long id, @RequestBody AddressService.AddressReq req) {
        addressService.update(id, req);
        return Result.ok();
    }

    @DeleteMapping("/api/addresses/{id}")
    public Result<Void> deleteAddress(@PathVariable Long id) {
        addressService.delete(id);
        return Result.ok();
    }

    @PutMapping("/api/addresses/{id}/default")
    public Result<Void> defaultAddress(@PathVariable Long id) {
        addressService.setDefault(id);
        return Result.ok();
    }

    @GetMapping("/api/favorites")
    public Result<PageResult<Map<String, Object>>> favorites(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(favoriteBrowseService.favorites(page, size));
    }

    @PostMapping("/api/favorites/{spuId}")
    public Result<Void> addFavorite(@PathVariable Long spuId) {
        favoriteBrowseService.addFavorite(spuId);
        return Result.ok();
    }

    @DeleteMapping("/api/favorites/{spuId}")
    public Result<Void> removeFavorite(@PathVariable Long spuId) {
        favoriteBrowseService.removeFavorite(spuId);
        return Result.ok();
    }

    @GetMapping("/api/history")
    public Result<PageResult<Map<String, Object>>> history(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(favoriteBrowseService.history(page, size));
    }

    @DeleteMapping("/api/history")
    public Result<Void> clearHistory() {
        favoriteBrowseService.clearHistory();
        return Result.ok();
    }

    @PostMapping("/api/orders")
    public Result<Map<String, Object>> submit(@RequestBody OrderSubmitService.SubmitReq req) {
        return Result.ok(orderSubmitService.submit(req));
    }

    @GetMapping("/api/orders")
    public Result<PageResult<Order>> myOrders(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Long customerId = SecurityContexts.requireUserId();
        LambdaQueryWrapper<Order> q = new LambdaQueryWrapper<>();
        q.eq(Order::getCustomerId, customerId)
                .eq(StringUtils.hasText(status), Order::getStatus, status)
                .orderByDesc(Order::getId);
        Page<Order> p = orderMapper.selectPage(new Page<>(page, size), q);
        return Result.ok(PageResult.of(p.getTotal(), p.getRecords()));
    }

    @GetMapping("/api/orders/{id}")
    public Result<Map<String, Object>> orderDetail(@PathVariable Long id) {
        Long customerId = SecurityContexts.requireUserId();
        Order order = orderMapper.selectById(id);
        if (order == null || !customerId.equals(order.getCustomerId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        Map<String, Object> m = new HashMap<>();
        m.put("order", order);
        m.put("items", orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, id)));
        m.put("shipments", shipmentMapper.selectList(new LambdaQueryWrapper<Shipment>().eq(Shipment::getOrderId, id)));
        return Result.ok(m);
    }

    @PostMapping("/api/orders/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id) {
        orderSubmitService.cancel(id);
        return Result.ok();
    }

    @PostMapping("/api/orders/{id}/confirm")
    public Result<Void> confirm(@PathVariable Long id) {
        orderSubmitService.confirmReceive(id);
        return Result.ok();
    }

    @GetMapping("/api/orders/{id}/logistics")
    public Result<List<Shipment>> logistics(@PathVariable Long id) {
        Long customerId = SecurityContexts.requireUserId();
        Order order = orderMapper.selectById(id);
        if (order == null || !customerId.equals(order.getCustomerId())) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return Result.ok(shipmentMapper.selectList(new LambdaQueryWrapper<Shipment>().eq(Shipment::getOrderId, id)));
    }

    @PostMapping("/api/payment/sandbox")
    public Result<Map<String, Object>> sandboxPay(@RequestBody Map<String, Object> body) {
        Long orderId = Long.valueOf(body.get("orderId").toString());
        return Result.ok(paymentSandboxService.pay(orderId));
    }

    @GetMapping("/api/payment/{orderId}/status")
    public Result<Map<String, Object>> payStatus(@PathVariable Long orderId) {
        return Result.ok(paymentSandboxService.status(orderId));
    }

    @PostMapping("/api/after-sales")
    public Result<Long> applyAfterSale(@RequestBody AfterSaleService.ApplyReq req) {
        return Result.ok(afterSaleService.apply(req));
    }

    @GetMapping("/api/after-sales")
    public Result<PageResult<?>> myAfterSales(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(afterSaleService.myPage(page, size, status));
    }

    @GetMapping("/api/after-sales/{id}")
    public Result<Map<String, Object>> afterSaleDetail(@PathVariable Long id) {
        return Result.ok(afterSaleService.myDetail(id));
    }

    @PostMapping("/api/reviews")
    @Operation(summary = "发表评价")
    public Result<Long> createReview(@RequestBody Map<String, Object> body) {
        Long customerId = SecurityContexts.requireUserId();
        if (!customerQueryApi.isActive(customerId)) {
            throw new BizException(ErrorCode.CUSTOMER_FROZEN);
        }
        Object itemRaw = body.get("orderItemId");
        Object ratingRaw = body.get("rating");
        if (itemRaw == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择评价的订单项");
        }
        if (ratingRaw == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写评分");
        }
        Long orderItemId;
        int rating;
        try {
            orderItemId = Long.valueOf(itemRaw.toString());
            rating = Integer.parseInt(ratingRaw.toString());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "orderItemId/rating 格式不正确");
        }
        if (rating < 1 || rating > 5) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评分须为 1~5");
        }
        OrderItem item = orderItemMapper.selectById(orderItemId);
        if (item == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "订单项不存在");
        }
        Order order = orderMapper.selectById(item.getOrderId());
        if (order == null || !customerId.equals(order.getCustomerId()) || !"COMPLETED".equals(order.getStatus())) {
            throw new BizException(ErrorCode.ORDER_STATUS_INVALID, "仅已完成订单可评价");
        }
        Long exists = reviewMapper.selectCount(new LambdaQueryWrapper<Review>().eq(Review::getOrderItemId, orderItemId));
        if (exists != null && exists > 0) {
            throw new BizException(ErrorCode.REVIEW_EXISTS);
        }
        Review review = new Review();
        review.setOrderItemId(orderItemId);
        review.setCustomerId(customerId);
        review.setSpuId(item.getSpuId());
        review.setRating(rating);
        review.setContent(body.get("content") == null ? null : body.get("content").toString());
        if (body.get("picKeys") instanceof List<?> pics) {
            review.setPics(pics.stream().map(Object::toString).toList());
        }
        review.setStatus("VISIBLE");
        reviewMapper.insert(review);
        return Result.ok(review.getId());
    }

    @GetMapping("/api/products/{spuId}/reviews")
    public Result<PageResult<Review>> productReviews(
            @PathVariable Long spuId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<Review> p = reviewMapper.selectPage(new Page<>(page, size), new LambdaQueryWrapper<Review>()
                .eq(Review::getSpuId, spuId)
                .eq(Review::getStatus, "VISIBLE")
                .orderByDesc(Review::getId));
        return Result.ok(PageResult.of(p.getTotal(), p.getRecords()));
    }
}
