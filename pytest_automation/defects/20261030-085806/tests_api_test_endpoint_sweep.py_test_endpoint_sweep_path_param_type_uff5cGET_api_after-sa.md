# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cGET /api/after-sales/{id}]-f62fda0a

- 运行：20261030-085806（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cGET /api/after-sales/{id}] 逆向-路径参数类型错误｜GET /api/after-sales/{id}
- 接口：GET /api/after-sales/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type GET /api/after-sales/{id}：HTTP 500
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
      "traceId": "724f63c29ed04839ac02c54eb482c799"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=9dd5321c88e24a3cab5feb8985f10be4 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/StorefrontController.java:266（endpoint）

```java
  261 |             @RequestParam(defaultValue = "10") int size) {
  262 |         return Result.ok(afterSaleService.myPage(page, size, status));
  263 |     }
  264 | 
  265 |     @GetMapping("/api/after-sales/{id}")
  266 |     public Result<Map<String, Object>> afterSaleDetail(@PathVariable Long id) {
  267 |         return Result.ok(afterSaleService.myDetail(id));
  268 |     }
  269 | 
  270 |     @PostMapping("/api/reviews")
  271 |     @Operation(summary = "发表评价")
```
