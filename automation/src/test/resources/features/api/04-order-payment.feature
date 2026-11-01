@api @regression
Feature: 下单、支付与订单状态

  @API-ORDER-001 @smoke
  Scenario: 小额订单收运费且幂等键复用
    Given 我用新会员身份登录
    When 我准备一个含 SKU "10028" 数量 1 的待支付订单
    Then 订单运费应为 8 且状态为 PENDING_PAY
    And 我用相同幂等键再次提交应返回同一订单

  @API-ORDER-002 @smoke
  Scenario: 满额订单免运费
    Given 我用新会员身份登录
    When 我准备一个含 SKU "10026" 数量 1 的待支付订单
    Then 订单运费应为 0 且状态为 PENDING_PAY

  @API-PAY-001 @smoke
  Scenario: 沙箱支付成功且禁止重复支付
    Given 我用新会员身份登录
    When 我准备一个含 SKU "10026" 数量 1 的待支付订单
    And 我对该订单发起沙箱支付
    Then 订单状态应变为 PENDING_SHIP 且交易号以 SANDBOX- 开头
    When 我再次支付同一订单应失败
    Then 支付业务码应为 40902

  @API-ORDER-003
  Scenario: 待支付订单可取消且已支付订单不可取消
    Given 我用新会员身份登录
    When 我准备一个含 SKU "10026" 数量 1 的待支付订单
    And 我取消该订单
    Then 订单状态应为 "CLOSED"
    When 我准备一个含 SKU "10026" 数量 1 的待支付订单
    And 我对该订单发起沙箱支付
    And 我取消该已支付订单应失败
    Then 取消失败业务码应为 40902

  @API-ORDER-004 @negative @smoke
  Scenario: 未登录访问订单列表返回 401
    When 我未登录访问订单列表
    Then 订单接口 HTTP 状态应为 401
