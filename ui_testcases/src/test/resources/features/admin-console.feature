Feature: 管理端登录与商品列表

  @UI-ADMIN-LOGIN-001
  Scenario: 管理员登录后进入工作台
    Given 管理员打开管理端并登录
    Then 应进入工作台且在售指标有值

  @UI-ADMIN-PRODUCT-001
  Scenario: 商品列表有数据且分页可用
    Given 管理员打开管理端并登录
    When 通过侧栏进入商品列表
    Then 商品表格应有数据且分页可用

  @UI-ADMIN-PRODUCT-002
  Scenario: 按关键字查询商品
    Given 管理员打开管理端并登录
    When 通过侧栏进入商品列表
    And 按商品名称关键字 "帐篷" 查询
    Then 查询结果应包含关键字 "帐篷"
