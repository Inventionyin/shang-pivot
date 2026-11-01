# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPOST /admin/orders/{id}/ship]-cda85a75

- 运行：20261030-074406（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPOST /admin/orders/{id}/ship] 逆向-路径参数类型错误｜POST /admin/orders/{id}/ship
- 接口：POST /admin/orders/{id}/ship
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type POST /admin/orders/{id}/ship：HTTP 500
assert 500 < 500
 +  where 500 = <Response [500]>.status_code

## 请求 / 响应

```json
{
  "request": {
    "query": null,
    "body": {},
    "raw": null
  },
  "response": {
    "status": 500,
    "body": {
      "code": 50000,
      "msg": "系统异常",
      "traceId": "a0f025ddd44642e799a11771d7f42f7f"
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
