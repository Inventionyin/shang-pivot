# 缺陷 API-LINK-040-7e23b79b

- 运行：20261030-074406（git b13bf61）
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
      "trackingNo": "SF62206722"
    },
    "raw": null
  },
  "response": {
    "status": 200,
    "body": {
      "code": 0,
      "msg": "success",
      "traceId": "232ef493e13c41bab24d6bdecb261b89"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/OrderController.java:115（endpoint）

```java
  110 |     }
  111 | 
  112 |     @PostMapping("/{id}/ship")
  113 |     @OpLog(module = "order", action = "ship")
  114 |     @Operation(summary = "订单发货", description = "对待发货订单录入物流信息并扣减库存")
  115 |     public Result<Void> ship(
  116 |             @Parameter(description = "订单ID", example = "1") @PathVariable Long id,
  117 |             @RequestBody OrderService.ShipReq req) {
  118 |         orderService.ship(id, req);
  119 |         return Result.ok();
  120 |     }
```
