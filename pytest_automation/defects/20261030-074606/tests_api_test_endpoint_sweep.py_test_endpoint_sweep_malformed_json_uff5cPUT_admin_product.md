# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPUT /admin/products/{id}]-98fb9e4c

- 运行：20261030-074606（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPUT /admin/products/{id}] 异常-畸形 JSON 请求体｜PUT /admin/products/{id}
- 接口：PUT /admin/products/{id}
- 分类：异常｜优先级：P2
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: malformed_json PUT /admin/products/{id}：HTTP 500
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
      "traceId": "ecc3336f7d5c40b1a038e3c69fbac3d5"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/ProductController.java:54（endpoint）

```java
   49 |     }
   50 | 
   51 |     @PutMapping("/{id}")
   52 |     @OpLog(module = "product", action = "update")
   53 |     @Operation(summary = "更新商品", description = "修改 SPU 信息并重建 SKU 列表")
   54 |     public Result<Void> update(
   55 |             @Parameter(description = "商品ID", example = "1") @PathVariable Long id,
   56 |             @RequestBody ProductService.ProductSaveReq req) {
   57 |         productService.update(id, req);
   58 |         return Result.ok();
   59 |     }
```
