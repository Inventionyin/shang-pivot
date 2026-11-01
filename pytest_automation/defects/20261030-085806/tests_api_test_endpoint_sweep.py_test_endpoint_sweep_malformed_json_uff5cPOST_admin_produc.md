# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/products]-a6b1cf2f

- 运行：20261030-085806（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/products] 异常-畸形 JSON 请求体｜POST /admin/products
- 接口：POST /admin/products
- 分类：异常｜优先级：P2
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: malformed_json POST /admin/products：HTTP 500
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
      "traceId": "e5aab545b6c64a5e819465689390553b"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=33a9a2aa1add436fb419c1dedf37e417 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/ProductController.java:47（endpoint）

```java
   42 |     }
   43 | 
   44 |     @PostMapping
   45 |     @OpLog(module = "product", action = "create")
   46 |     @Operation(summary = "创建商品", description = "新建 SPU 及 SKU，默认状态为 DRAFT")
   47 |     public Result<Long> create(@RequestBody ProductService.ProductSaveReq req) {
   48 |         return Result.ok(productService.create(req));
   49 |     }
   50 | 
   51 |     @PutMapping("/{id}")
   52 |     @OpLog(module = "product", action = "update")
```
