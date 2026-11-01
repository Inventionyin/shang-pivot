@api @smoke
Feature: C 端会员认证

  @API-AUTH-001 @regression
  Scenario: 新手机号用演示验证码登录并自动注册
    When 我用新手机号和演示验证码登录
    Then 登录应成功并返回双令牌
    And 我用该令牌查询个人资料应返回同一手机号

  @API-AUTH-002 @negative @regression
  Scenario: 验证码错误时拒绝登录
    When 我用演示会员手机号和错误验证码登录
    Then 登录业务码应为 40101 且不返回令牌

  @API-AUTH-003 @negative @regression
  Scenario: 冻结会员不能登录
    When 我用冻结会员手机号登录
    Then 登录业务码应为 40310
    And 业务提示应包含 "账号不可用"
