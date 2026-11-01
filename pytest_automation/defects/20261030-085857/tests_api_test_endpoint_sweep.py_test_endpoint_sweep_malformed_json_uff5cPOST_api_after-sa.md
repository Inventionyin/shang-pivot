# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /api/after-sales]-df20794f

- 运行：20261030-085857（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /api/after-sales] 异常-畸形 JSON 请求体｜POST /api/after-sales
- 接口：POST /api/after-sales
- 分类：异常｜优先级：P2
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: malformed_json POST /api/after-sales：HTTP 500
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
      "traceId": "42189e18c4d444c7a1036a9d348350c6"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=c848f0e243e64a7fb7fa5954540279a1 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/StorefrontController.java:253（endpoint）

```java
  248 |     public Result<Map<String, Object>> payStatus(@PathVariable Long orderId) {
  249 |         return Result.ok(paymentSandboxService.status(orderId));
  250 |     }
  251 | 
  252 |     @PostMapping("/api/after-sales")
  253 |     public Result<Long> applyAfterSale(@RequestBody AfterSaleService.ApplyReq req) {
  254 |         return Result.ok(afterSaleService.apply(req));
  255 |     }
  256 | 
  257 |     @GetMapping("/api/after-sales")
  258 |     public Result<PageResult<?>> myAfterSales(
```
