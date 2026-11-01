@known-defect @exception
Feature: 已知健壮性缺陷

  @DEF-INPUT-001
  Scenario: 畸形 JSON 应返回 4xx 而不是 500
    When 我发送格式错误的 JSON 登录请求
    Then 登录接口 HTTP 状态应为 400
    And 响应体不应包含堆栈信息

  @DEF-INPUT-002
  Scenario: 错误 Content-Type 应返回 415 而不是 500
    When 我使用 Content-Type "text/plain" 发送登录请求
    Then 登录接口 HTTP 状态应为 415
    And 响应体不应包含堆栈信息

  @DEF-CART-001
  Scenario Outline: 非法数量类型应返回参数错误
    Given 我用新会员身份登录
    When 我向购物车加入 SKU "10001" 数量原始值 <raw>
    Then 购物车业务码应为 40000

    Examples:
      | raw     |
      | "1.5"   |
      | "abc"   |

  @DEF-CONC-001
  Scenario: 同一幂等键并发提交不应出现系统异常
    Given 我用新会员身份登录
    And 我通过接口准备默认收货地址
    When 我使用同一幂等键并发提交 2 次下单请求
    Then 两次提交应返回同一订单号
