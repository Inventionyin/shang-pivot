# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cDELETE /api/cart/items/{id}]-9b5b8a4c

- 运行：20261030-085806（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cDELETE /api/cart/items/{id}] 逆向-路径参数类型错误｜DELETE /api/cart/items/{id}
- 接口：DELETE /api/cart/items/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type DELETE /api/cart/items/{id}：HTTP 500
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
      "traceId": "ed220a00e04d4026bb872f46fc32c6ce"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=9fff91fbe70f4d13960a4fb73522c2f6 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/StorefrontController.java:115（endpoint）

```java
  110 |         cartService.updateItem(id, qty, selected);
  111 |         return Result.ok();
  112 |     }
  113 | 
  114 |     @DeleteMapping("/api/cart/items/{id}")
  115 |     public Result<Void> removeCart(@PathVariable Long id) {
  116 |         cartService.removeItem(id);
  117 |         return Result.ok();
  118 |     }
  119 | 
  120 |     @PostMapping("/api/cart/clear-invalid")
```
