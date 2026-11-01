package com.shangpivot.common.result;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    SUCCESS(0, "success"),
    BAD_REQUEST(40000, "参数校验失败"),
    UNAUTHORIZED(40100, "未认证"),
    LOGIN_FAILED(40101, "账号或密码错误"),
    TOKEN_EXPIRED(40102, "Token已过期"),
    TOKEN_INVALID(40103, "Token无效"),
    ACCOUNT_LOCKED(40104, "账号已锁定，请稍后再试"),
    FORBIDDEN(40300, "无权限"),
    CUSTOMER_FROZEN(40310, "账号不可用"),
    NOT_FOUND(40400, "资源不存在"),
    PRODUCT_UNAVAILABLE(40410, "商品不存在或未上架"),
    CONFLICT(40900, "状态不允许"),
    STOCK_INSUFFICIENT(40901, "库存不足"),
    ORDER_STATUS_INVALID(40902, "订单状态不允许"),
    AFTER_SALE_STATUS_INVALID(40903, "售后状态不允许"),
    REVIEW_EXISTS(40904, "评价已存在"),
    SYSTEM_ERROR(50000, "系统异常");

    private final int code;
    private final String msg;
}
