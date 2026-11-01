Feature: 商城订单状态流转（取消待支付订单）

  @UI-MALL-ORDER-001
  Scenario: 取消待支付订单后状态变为已关闭
    Given 会员已通过界面登录
    And 购物车中已有一件商品
    And 通过接口造一笔待支付订单
    When 在订单详情页取消该订单
    Then 订单状态应变为已关闭
