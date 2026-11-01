Feature: 商城会员登录

  @UI-MALL-LOGIN-001
  Scenario: 演示会员用固定验证码登录成功
    Given 会员打开登录页并填入演示手机号
    When 先点获取验证码
    Then 应提示验证码已发送
    And 填入固定验证码并提交
    Then 应登录成功并进入商城

  @UI-MALL-LOGIN-002
  Scenario: 验证码错误时给出明确提示
    Given 会员打开登录页
    When 填入错误的验证码 "000000" 并提交
    Then 应提示验证码错误

  @UI-MALL-LOGIN-003
  Scenario: 手机号格式不合法时行内报错
    Given 会员打开登录页
    When 填入不合法的手机号 "123"
    And 提交登录表单
    Then 应给出手机号格式的行内错误
