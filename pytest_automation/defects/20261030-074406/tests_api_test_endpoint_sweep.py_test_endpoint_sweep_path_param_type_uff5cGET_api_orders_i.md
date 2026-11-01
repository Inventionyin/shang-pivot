# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cGET /api/orders/{id}/logistics]-90a2b4ca

- 运行：20261030-074406（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cGET /api/orders/{id}/logistics] 逆向-路径参数类型错误｜GET /api/orders/{id}/logistics
- 接口：GET /api/orders/{id}/logistics
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type GET /api/orders/{id}/logistics：HTTP 500
assert 500 < 500
 +  where 500 = <Response [500]>.status_code

## 请求 / 响应

```json
{
  "request": {
    "query": {
      "page": 1,
      "size": 1
    },
    "body": null,
    "raw": null
  },
  "response": {
    "status": 500,
    "body": {
      "code": 50000,
      "msg": "系统异常",
      "traceId": "b922476226e041db96118ed75c20668c"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/StorefrontController.java:232（endpoint）

```java
  227 |         orderSubmitService.confirmReceive(id);
  228 |         return Result.ok();
  229 |     }
  230 | 
  231 |     @GetMapping("/api/orders/{id}/logistics")
  232 |     public Result<List<Shipment>> logistics(@PathVariable Long id) {
  233 |         Long customerId = SecurityContexts.requireUserId();
  234 |         Order order = orderMapper.selectById(id);
  235 |         if (order == null || !customerId.equals(order.getCustomerId())) {
  236 |             throw new BizException(ErrorCode.NOT_FOUND);
  237 |         }
```
