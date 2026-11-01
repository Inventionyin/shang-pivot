# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/products/{id}/on]-adba1492

- 运行：20261030-085806（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/products/{id}/on] 逆向-路径参数类型错误｜PUT /admin/products/{id}/on
- 接口：PUT /admin/products/{id}/on
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type PUT /admin/products/{id}/on：HTTP 500
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
      "traceId": "963b7d5784fd4ba1bd421be51c64c5a1"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=6e5f7645ff6847e5b1d1da6a03f8f3cc 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/ProductController.java:64（endpoint）

```java
   59 |     }
   60 | 
   61 |     @PutMapping("/{id}/on")
   62 |     @OpLog(module = "product", action = "onShelf")
   63 |     @Operation(summary = "商品上架", description = "将商品状态变更为 ON，需满足主图、SKU、价格等校验")
   64 |     public Result<Void> on(
   65 |             @Parameter(description = "商品ID", example = "1") @PathVariable Long id) {
   66 |         productService.changeStatus(id, "ON");
   67 |         return Result.ok();
   68 |     }
   69 | 
```
