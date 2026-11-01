@api @regression
Feature: 管理端接口与权限隔离

  @API-ADMIN-001 @smoke
  Scenario: 管理员登录并读取看板
    When 我以管理员身份登录
    Then 管理看板应返回数字指标且商品数大于 0

  @API-ADMIN-002 @smoke
  Scenario: 管理员按关键字查询商品
    When 我以管理员身份登录
    And 我按关键字 "iPhone" 查询后台商品
    Then 后台商品结果应全部包含关键字

  @API-ADMIN-003 @known-defect
  Scenario: 管理员对已支付订单发货
    Given 我用新会员身份登录
    When 我准备一个含 SKU "10026" 数量 1 的待支付订单
    And 我把该待支付订单支付并发货 "SF"
    Then 订单状态应变为 "PENDING_RECEIVE"
    And 管理端订单详情应包含该运单号

  @API-ADMIN-004 @negative @smoke
  Scenario: 会员令牌不能访问管理端
    Given 我用新会员身份登录
    When 我用会员令牌访问管理端订单
    Then 管理端 HTTP 状态应为 401
