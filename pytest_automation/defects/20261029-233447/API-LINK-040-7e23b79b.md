# 缺陷 API-LINK-040-7e23b79b

- 运行：20261029-233447（git b13bf61）
- 用例：API-LINK-040 API-LINK-040 评价订单项合规但缺评分
- 接口：POST /admin/orders/{id}/ship
- 分类：参数组合｜优先级：P0
- 断言意图：预期 400（缺陷 D-14：当前 500）
- 失败摘要：AssertionError: 业务码期望 40000 实际 50000；msg=系统异常

## 请求 / 响应

```json
{
  "request": {
    "query": null,
    "body": {
      "carrier": "顺丰速运",
      "trackingNo": "SF92534786"
    },
    "raw": null
  },
  "response": {
    "status": 200,
    "body": {
      "code": 0,
      "msg": "success",
      "traceId": "40e2251a0e664b12828a87e36e5ab0b9"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- 未定位（非 500 或日志缺失）

```java

```
