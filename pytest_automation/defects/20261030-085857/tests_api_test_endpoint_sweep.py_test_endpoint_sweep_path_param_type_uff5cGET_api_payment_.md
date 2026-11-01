# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cGET /api/payment/{orderId}/status]-f34ad70f

- 运行：20261030-085857（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cGET /api/payment/{orderId}/status] 逆向-路径参数类型错误｜GET /api/payment/{orderId}/status
- 接口：GET /api/payment/{orderId}/status
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type GET /api/payment/{orderId}/status：HTTP 500
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
      "traceId": "3ac764b7f7fa4890828eab26873218df"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=d23b5ba24d9f4d618e14d1ca061f04af 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/StorefrontController.java:248（endpoint）

```java
  243 |         Long orderId = Long.valueOf(body.get("orderId").toString());
  244 |         return Result.ok(paymentSandboxService.pay(orderId));
  245 |     }
  246 | 
  247 |     @GetMapping("/api/payment/{orderId}/status")
  248 |     public Result<Map<String, Object>> payStatus(@PathVariable Long orderId) {
  249 |         return Result.ok(paymentSandboxService.status(orderId));
  250 |     }
  251 | 
  252 |     @PostMapping("/api/after-sales")
  253 |     public Result<Long> applyAfterSale(@RequestBody AfterSaleService.ApplyReq req) {
```
