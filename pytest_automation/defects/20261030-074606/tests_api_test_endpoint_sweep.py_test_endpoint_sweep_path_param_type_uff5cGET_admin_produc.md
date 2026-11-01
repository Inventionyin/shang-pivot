# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cGET /admin/products/{id}]-4b563699

- 运行：20261030-074606（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cGET /admin/products/{id}] 逆向-路径参数类型错误｜GET /admin/products/{id}
- 接口：GET /admin/products/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type GET /admin/products/{id}：HTTP 500
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
      "traceId": "ab743d8757104b57aebe726356728acd"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/ProductController.java:39（endpoint）

```java
   34 |         return Result.ok(productService.page(page, size, name, status, categoryId));
   35 |     }
   36 | 
   37 |     @GetMapping("/{id}")
   38 |     @Operation(summary = "获取商品详情", description = "返回 SPU 信息及关联 SKU 列表")
   39 |     public Result<Map<String, Object>> detail(
   40 |             @Parameter(description = "商品ID", example = "1") @PathVariable Long id) {
   41 |         return Result.ok(productService.detail(id));
   42 |     }
   43 | 
   44 |     @PostMapping
```
