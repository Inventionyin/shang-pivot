# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /api/orders]-bc75cf35

- 运行：20261030-074606（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /api/orders] 异常-畸形 JSON 请求体｜POST /api/orders
- 接口：POST /api/orders
- 分类：异常｜优先级：P2
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: malformed_json POST /api/orders：HTTP 500
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
      "traceId": "8ac37324fc0448cabeb88892390151db"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
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
