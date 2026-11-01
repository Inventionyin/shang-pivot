@ui @smoke
Feature: 商城浏览

  @UI-MALL-HOME-001
  Scenario: 首页展示品牌与商品卡片
    When 我打开商城首页
    Then 首页应展示品牌名和商品卡片

  @UI-MALL-PRODUCT-001
  Scenario: 从首页进入商品详情
    When 我打开商城首页
    And 我打开第一个商品详情
    Then 商品详情应显示标题

  @UI-MALL-SEARCH-001 @negative
  Scenario: 搜索不存在关键词展示空态
    When 我打开搜索页搜索不存在关键词
    Then 搜索页应展示空态和推荐
