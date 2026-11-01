@known-defect @security
Feature: 已知安全缺陷

  @DEF-SEC-001
  Scenario: alg=none 令牌应被拒绝而不是系统异常
    Given 我有一个有效会员 token
    When 我使用 "alg=none" 访问购物车
    Then 令牌接口 HTTP 状态应为 401
