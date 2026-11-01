@ui @regression
Feature: 商城订单取消

  @UI-MALL-ORDER-001
  Scenario: 取消待支付订单后状态关闭
    Given 我用新会员身份登录
    And 我准备一个含 SKU "10026" 数量 1 的待支付订单
    And 我已通过接口登录商城并注入登录态
    When 我在订单详情页取消该订单
    Then 订单详情应显示已关闭
