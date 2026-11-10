@ui @ai
Feature: 商城 AI 助手会话恢复

  @AI-CHAT-RESUME-001
  Scenario: 从 AI 商品卡进入详情再返回，会话不丢失
    Given 我用新手机号登录商城并打开 AI 助手
    When 我在 AI 助手发送 "推荐一个手机，预算两千左右"
    Then AI 助手应展示商品卡片
    When 我点击第一张 AI 商品卡片
    And 我返回 AI 助手页面
    Then AI 助手对话应包含 "推荐一个手机，预算两千左右"
    And AI 助手应展示商品卡片

  @AI-CHAT-RESUME-002
  Scenario: 刷新 AI 助手页面后恢复当前进行中会话
    Given 我用新手机号登录商城并打开 AI 助手
    When 我在 AI 助手发送 "推荐一个手机，预算两千左右"
    Then AI 助手应展示商品卡片
    When 我刷新 AI 助手页面
    Then AI 助手对话应包含 "推荐一个手机，预算两千左右"
    And AI 助手应展示商品卡片

  @AI-CHAT-RESUME-003
  Scenario: 新对话真正新建，旧会话保留在历史会话
    Given 我用新手机号登录商城并打开 AI 助手
    When 我在 AI 助手发送 "推荐一个手机，预算两千左右"
    Then AI 助手应展示商品卡片
    When 我点击 AI 新对话
    Then AI 助手对话不应包含 "推荐一个手机，预算两千左右"
    When 我在 AI 助手发送 "推荐一个耳机"
    Then AI 助手应展示商品卡片
    When 我打开 AI 历史会话
    Then AI 历史会话应包含 "Redmi K70"

  @AI-CHAT-RESUME-004
  Scenario: 人工体验入口从商品详情返回后同样恢复会话
    Given 我用新手机号登录商城并打开人工体验 AI 助手
    When 我在 AI 助手发送 "推荐一个手机，预算两千左右"
    Then AI 助手应展示商品卡片
    When 我点击第一张 AI 商品卡片
    And 我返回 AI 助手页面
    Then AI 助手对话应包含 "推荐一个手机，预算两千左右"

  @AI-CHAT-RESUME-005 @h5
  Scenario: H5 悬浮球进入 AI 后从商品详情返回仍恢复会话
    Given 我用新手机号登录商城并通过 H5 悬浮球打开 AI 助手
    When 我在 AI 助手发送 "推荐一个手机，预算两千左右"
    Then AI 助手应展示商品卡片
    When 我点击第一张 AI 商品卡片
    And 我返回 AI 助手页面
    Then AI 助手对话应包含 "推荐一个手机，预算两千左右"
    And AI 助手应展示商品卡片

  @AI-CHAT-ADDCART-REF-001
  Scenario: 用“现在买/把上面那个加进购物车”直接操作上文商品
    Given 我用新手机号登录商城并打开 AI 助手
    When 我在 AI 助手发送 "我要买冰箱"
    Then AI 助手应展示商品卡片
    When 我在 AI 助手发送 "我现在买"
    Then AI 助手应显示待确认加购卡片
    And AI 助手对话应包含 "请确认"
    When 我在 AI 助手发送 "帮我把上面那个美的风冷冰箱加进购物车"
    Then AI 助手应显示待确认加购卡片
    And AI 助手应展示商品卡片

  @AI-CHAT-ADDCART-BUTTON-001
  Scenario: 点击商品卡“加入购物车”生成待确认卡
    Given 我用新手机号登录商城并打开 AI 助手
    When 我在 AI 助手发送 "推荐一个手机，预算两千左右"
    Then AI 助手应展示商品卡片
    When 我点击第一张 AI 商品卡片的加入购物车
    Then AI 助手应显示待确认加购卡片
    And AI 助手对话应包含 "请确认"

  @AI-CHAT-SKU-OPTION-001
  Scenario: 多规格商品先展示规格快捷选项，选择后生成加购卡
    Given 我用新手机号登录商城并打开 AI 助手
    When 我在 AI 助手发送 "推荐 iPhone 15 Pro"
    Then AI 助手应展示商品卡片
    When 我在 AI 助手发送 "把iPhone 15 Pro加进购物车"
    Then AI 助手对话应包含 "规格"
    When 我点击第一个 AI 规格快捷选项
    Then AI 助手应显示待确认加购卡片

  @AI-CHAT-SCROLL-001
  Scenario: 长对话消息区可以稳定滚动到底部
    Given 我用新手机号登录商城并打开 AI 助手
    When 我在 AI 助手发送 "推荐一个冰箱"
    And 我在 AI 助手发送 "再推荐一个手机"
    And 我在 AI 助手发送 "再推荐一个耳机"
    Then AI 助手消息区应滚动到底部
