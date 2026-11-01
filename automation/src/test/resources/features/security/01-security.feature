@api @security @regression
Feature: 安全场景

  @SEC-AUTH-001
  Scenario Outline: 非法令牌不能访问受保护接口
    Given 我有一个有效会员 token
    When 我使用 "<tokenType>" 访问购物车
    Then 令牌接口 HTTP 状态应为 401
    And 响应不应包含用户信息

    Examples:
      | tokenType |
      | 无 token   |
      | 空 token   |
      | 篡改签名   |

  @SEC-SQLI-001
  Scenario: 搜索接口 SQL 注入不生效
    When 我匿名搜索关键词 "' OR '1'='1"
    Then 搜索结果总数应 "等于 0"
    And 响应不应包含 SQL 错误

  @SEC-CORS-001
  Scenario: 恶意 Origin 预检被拒绝
    When 我发送来自恶意 Origin 的预检请求
    Then 预检请求应被拒绝

  @SEC-HEADER-001
  Scenario: 首页返回基础安全响应头
    When 我匿名请求首页并检查安全响应头
    Then 安全响应头应存在

  @SEC-DATA-001
  Scenario: 会员资料不返回密码或令牌
    Given 我用新会员身份登录
    When 我查询会员资料
    Then 资料响应不应包含密码或令牌
