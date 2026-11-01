@ui @regression
Feature: 商城购物车与结算

  @UI-MALL-CART-001 @smoke
  Scenario: 商品详情加购后购物车可见
    Given 我已通过接口登录商城并注入登录态
    When 我在商品详情页把商品加入购物车
    Then 购物车应包含该商品

  @UI-MALL-CART-002 @negative
  Scenario: 未勾选商品去结算被拦截
    Given 我已通过接口登录商城并注入登录态
    When 我在商品详情页把商品加入购物车
    And 我取消购物车所有勾选并点击去结算
    Then 应提示请先勾选商品且停留在购物车

  @UI-MALL-CHECKOUT-001
  Scenario: 结算页展示地址与金额
    Given 我已通过接口登录商城并注入登录态
    And 我通过接口准备默认收货地址
    When 我在商品详情页把商品加入购物车
    And 我勾选商品并进入结算页
    Then 结算页应展示已选地址、商品清单和应付金额

  @UI-MALL-E2E-001 @e2e @smoke
  Scenario: 完整购买旅程：加购-结算-支付-订单
    Given 我已通过接口登录商城并注入登录态
    And 我通过接口准备默认收货地址
    When 我在商品详情页把商品加入购物车
    And 我勾选商品并进入结算页
    Then 结算页应展示已选地址、商品清单和应付金额
    When 我提交订单并完成沙箱支付
    Then 通过接口查询订单状态应为 "PENDING_SHIP"
