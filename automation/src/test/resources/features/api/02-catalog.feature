@api @smoke
Feature: C 端商品浏览与契约

  @API-CATALOG-001 @regression
  Scenario: 首页返回品牌、运营位与商品流
    When 我匿名请求首页
    Then 首页应返回非空商品流和运营位
    And 首页响应应符合契约

  @API-CATALOG-002 @regression
  Scenario: 商品列表分页与价格升序
    When 我匿名请求第 1 页商品并按价格升序
    Then 商品总数应大于 0 且每页 10 条
    And 商品价格应按升序排列

  @API-CATALOG-003 @regression
  Scenario: 商品详情返回 SKU 与库存
    When 我匿名请求商品 "101" 的详情
    Then 详情应返回 SKU 列表且库存非负
    And 详情响应应符合契约

  @API-CATALOG-004 @negative @regression
  Scenario Outline: 搜索关键词的命中与未命中
    When 我匿名搜索关键词 "<keyword>"
    Then 搜索结果总数应 "<expectation>"

    Examples:
      | keyword        | expectation |
      | iPhone         | 大于 0      |
      | 不存在的商品xyz | 等于 0      |
