Feature: 环境自检 —— 两个站点必须真的能打开

  @UI-ENV-001
  Scenario: 商城首页可访问
    Given 商城站点可访问
    Then 页面标题应包含商城名称

  @UI-ENV-002
  Scenario: 管理端登录页可访问
    Given 管理端站点可访问
    Then 应看到管理端登录表单
