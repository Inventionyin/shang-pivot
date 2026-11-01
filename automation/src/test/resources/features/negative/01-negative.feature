@api @negative @regression
Feature: 逆向场景

  @NEG-IDOR-001
  Scenario: 用户 A 不能查看用户 B 的订单
    Given 用户 A 和用户 B 各自有一笔待支付订单
    When 用户 A 使用自己的 token 请求用户 B 的订单详情
    Then 订单越权访问应被拒绝
    And 响应不应包含用户 B 的手机号

  @NEG-IDOR-002
  Scenario: 用户 A 不能修改用户 B 的地址
    Given 用户 A 和用户 B 各自有一笔待支付订单
    When 用户 A 尝试修改用户 B 的收货地址
    Then 地址越权访问应被拒绝

  @NEG-AUTH-001
  Scenario: 管理员 token 不能访问会员资料
    Given 我用新会员身份登录
    When 我使用管理员 token 访问会员资料接口
    Then 会员接口 HTTP 状态应为 401

  @NEG-MASS-001
  Scenario: 资料更新不能越权修改状态
    Given 我用新会员身份登录
    When 我提交包含 status 字段的资料更新请求
    Then 会员状态应保持 "NORMAL"

  @NEG-PRICE-001
  Scenario: 客户端篡改金额无效
    Given 我用新会员身份登录
    And 我通过接口准备默认收货地址
    When 我提交一笔金额被篡改的订单
    Then 订单应付金额应为 "199.00"

  @NEG-STATE-001
  Scenario: 已支付订单不能取消
    Given 我用新会员身份登录
    When 我准备一个含 SKU "10026" 数量 1 的待支付订单
    And 我对该订单发起沙箱支付
    When 我取消该已支付订单应失败
    Then 取消失败业务码应为 40902

  @NEG-ID-001
  Scenario: 不存在的订单不泄露信息
    Given 我用新会员身份登录
    When 我使用不存在的订单号查询订单
    Then 订单查询应返回不存在
