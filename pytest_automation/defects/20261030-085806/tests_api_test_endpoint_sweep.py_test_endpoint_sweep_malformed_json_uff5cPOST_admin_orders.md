# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/orders/{id}/ship]-b8ce03f0

- 运行：20261030-085806（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/orders/{id}/ship] 异常-畸形 JSON 请求体｜POST /admin/orders/{id}/ship
- 接口：POST /admin/orders/{id}/ship
- 分类：异常｜优先级：P2
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: malformed_json POST /admin/orders/{id}/ship：HTTP 500
assert 500 < 500
 +  where 500 = <Response [500]>.status_code

## 请求 / 响应

```json
{
  "request": {
    "query": null,
    "body": null,
    "raw": "{\"broken\":"
  },
  "response": {
    "status": 500,
    "body": {
      "code": 50000,
      "msg": "系统异常",
      "traceId": "76b9d2926aec4cd4bd2a05483a622eee"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=772dddf891f348be86c37d8e32f1bbc0 的日志（BACKEND_LOG 未配置或日志已轮转）
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
