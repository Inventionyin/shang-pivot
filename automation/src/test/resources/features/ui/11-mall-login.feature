@ui @smoke
Feature: 商城登录

  @UI-MALL-LOGIN-001
  Scenario: 新手机号验证码登录成功
    When 我用新手机号和演示验证码通过界面登录商城
    Then 商城应显示已登录状态

  @UI-MALL-LOGIN-002 @negative
  Scenario: 验证码错误时展示行内错误
    When 我用演示会员手机号和错误验证码通过界面登录商城
    Then 登录页应提示 "验证码错误，请重新获取"
