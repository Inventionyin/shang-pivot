@ui @smoke
Feature: 管理端控制台

  @UI-ADMIN-LOGIN-001
  Scenario: 管理员登录进入工作台
    When 我通过界面登录管理端
    Then 管理端看板应显示工作台和数字指标

  @UI-ADMIN-PRODUCT-001
  Scenario: 商品列表关键字搜索
    Given 我已通过接口登录管理端并注入登录态
    When 我打开后台商品列表并搜索 "iPhone"
    Then 后台商品列表每一行都应包含 "iPhone"
