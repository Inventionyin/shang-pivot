@api @regression
Feature: 购物车与收货地址

  @API-CART-001 @smoke
  Scenario: 购物车增改选删与金额联动
    Given 我用新会员身份登录
    When 我向购物车加入 SKU "10001" 数量 2
    Then 购物车应包含该商品且金额等于单价乘数量
    When 我把该购物车项数量改为 3
    Then 购物车金额应按新数量更新
    When 我取消该购物车项的勾选
    Then 购物车已选金额应为 0
    When 我删除该购物车项
    Then 购物车应不再包含该商品

  @API-CART-002 @negative
  Scenario: 超过库存加购被拒绝
    Given 我用新会员身份登录
    When 我向购物车加入 SKU "10001" 数量 100000 应失败
    Then 购物车业务码应为 40901
    And 业务提示应包含 "库存不足"

  @API-CART-003 @negative @smoke
  Scenario: 未登录访问购物车返回 401
    When 我未登录访问购物车
    Then 购物车接口 HTTP 状态应为 401

  @API-ADDR-001
  Scenario: 地址创建与默认地址切换
    Given 我用新会员身份登录
    When 我创建一个默认收货地址
    Then 收货地址列表应包含该地址且为默认
    When 我再创建一个收货地址并设为默认
    Then 原地址应不再是默认地址
