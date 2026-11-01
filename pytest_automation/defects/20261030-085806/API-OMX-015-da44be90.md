# 缺陷 API-OMX-015-da44be90

- 运行：20261030-085806（git b13bf61）
- 用例：API-OMX-015 API-OMX-015 【异常】skuId 非数字
- 接口：POST /api/orders
- 分类：契约/其他｜优先级：P0
- 断言意图：应 400（缺陷 D-17：当前 500）
- 失败摘要：AssertionError: 业务码期望 40000 实际 50000；msg=系统异常

## 请求 / 响应

```json
{
  "request": {
    "query": null,
    "body": {
      "addressId": "2115951762889322498",
      "skuId": "abc",
      "quantity": 1,
      "idempotentKey": "order-1793317187422-7c786a"
    },
    "raw": null
  },
  "response": {
    "status": 500,
    "body": {
      "code": 50000,
      "msg": "系统异常",
      "traceId": "ccd0e10c7ae346d59ac0624d0abf69b7"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=742b2794d4f3434bbdff2afe00f01249 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/StorefrontController.java:187（endpoint）

```java
  182 |         favoriteBrowseService.clearHistory();
  183 |         return Result.ok();
  184 |     }
  185 | 
  186 |     @PostMapping("/api/orders")
  187 |     public Result<Map<String, Object>> submit(@RequestBody OrderSubmitService.SubmitReq req) {
  188 |         return Result.ok(orderSubmitService.submit(req));
  189 |     }
  190 | 
  191 |     @GetMapping("/api/orders")
  192 |     public Result<PageResult<Order>> myOrders(
```
