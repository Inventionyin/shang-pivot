@api @exception @regression
Feature: 异常场景

  @EXC-INPUT-001
  Scenario: 缺少幂等键的订单被拒绝
    Given 我用新会员身份登录
    And 我通过接口准备默认收货地址
    When 我提交缺少幂等键的订单
    Then 下单业务码应为 40000

  @EXC-INPUT-002
  Scenario: 不存在的 SKU 被拒绝
    Given 我用新会员身份登录
    And 我通过接口准备默认收货地址
    When 我提交 SKU "99999999" 不存在的订单
    Then 下单业务码应为 40410

  @EXC-STATE-001
  Scenario: 已取消订单不能支付
    Given 我用新会员身份登录
    When 我准备一个含 SKU "10026" 数量 1 的待支付订单
    And 我取消该订单
    When 我尝试对该订单发起沙箱支付
    Then 支付业务码应为 40902
