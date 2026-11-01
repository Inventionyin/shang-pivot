@api @boundary @regression
Feature: 边界值场景

  @BND-CART-001
  Scenario Outline: 购物车数量边界
    Given 我用新会员身份登录
    When 我尝试向购物车加入 SKU "10001" 数量 <quantity>
    Then 购物车业务码应为 <code>
    And 购物车中该商品数量应为 <saved>

    Examples:
      | quantity | code  | saved |
      | -1       | 40000 | 0     |
      | 0        | 40000 | 0     |
      | 1        | 0     | 1     |
      | 999999   | 40901 | 0     |

  @BND-CART-002
  Scenario: 购物车数量等于库存与库存加一
    Given 我用新会员身份登录
    And 我查询 SPU "101" 中 SKU "10001" 的可售库存
    When 我向购物车加入该 SKU 数量为库存
    Then 购物车业务码应为 0
    When 我向购物车加入该 SKU 数量为库存加 1
    Then 购物车业务码应为 40901

  @BND-CATALOG-001
  Scenario Outline: 商品分页边界
    When 我匿名请求商品分页 page=<page> size=<size>
    Then 分页业务码应为 0
    And 分页返回记录数应不超过 <max>

    Examples:
      | page | size | max |
      | 0    | 10   | 10  |
      | 1    | 0    | 0   |
      | 1    | 101  | 30  |
      | 999  | 10   | 0   |

  @BND-AUTH-001
  Scenario Outline: 手机号长度边界
    When 我用手机号 "<phone>" 和验证码 "123456" 登录
    Then 登录接口 HTTP 状态应为 <http>
    And 登录业务码应为 <code>

    Examples:
      | phone        | http | code  |
      | 1390000000   | 400  | 40000 |
      | 13900000000  | 200  | 0     |
      | 139000000001 | 400  | 40000 |
      | abcdefghijk  | 400  | 40000 |

  @BND-AUTH-002
  Scenario Outline: 验证码长度边界
    When 我用新手机号和验证码 "<code>" 登录
    Then 登录业务码应为 <expected>

    Examples:
      | code    | expected |
      | 12345   | 40101    |
      | 123456  | 0        |
      | 1234567 | 40101    |
      | abc     | 40101    |

  @BND-ADDR-001
  Scenario: 第 21 条收货地址被拒绝
    Given 我用新会员身份登录
    When 我创建 20 条收货地址
    Then 第 21 条收货地址创建应失败
    And 业务提示应包含 "最多 20 条"
