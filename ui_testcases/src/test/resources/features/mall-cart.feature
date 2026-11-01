Feature: 商城购物车与下单前确认

  Background:
    Given 会员已通过界面登录

  @UI-MALL-CART-001
  Scenario: 首页商品加入购物车成功
    When 进入首页并打开第一个商品的详情
    And 选择第一个有货规格并加入购物车
    Then 购物车中应出现该商品

  @UI-MALL-CART-002
  Scenario: 未勾选商品去结算时被拦住
    Given 购物车中已有一件商品
    When 不勾选任何商品直接去结算
    Then 应提示先勾选商品

  @UI-MALL-CART-003
  Scenario: 勾选商品后进入确认订单页并可复核金额
    Given 购物车中已有一件商品
    When 勾选该商品并去结算
    Then 应进入确认订单页并展示应付金额
