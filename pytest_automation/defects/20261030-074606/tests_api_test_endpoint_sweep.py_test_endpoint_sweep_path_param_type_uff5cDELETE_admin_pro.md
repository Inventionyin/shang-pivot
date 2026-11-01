# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cDELETE /admin/products/{id}]-db36018c

- 运行：20261030-074606（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cDELETE /admin/products/{id}] 逆向-路径参数类型错误｜DELETE /admin/products/{id}
- 接口：DELETE /admin/products/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type DELETE /admin/products/{id}：HTTP 500
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
      "traceId": "8cd04df01c9842bba4b5e5d4a8aa1782"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/ProductController.java:89（endpoint）

```java
   84 |     }
   85 | 
   86 |     @DeleteMapping("/{id}")
   87 |     @OpLog(module = "product", action = "delete")
   88 |     @Operation(summary = "删除商品", description = "仅草稿状态商品可删除")
   89 |     public Result<Void> delete(
   90 |             @Parameter(description = "商品ID", example = "1") @PathVariable Long id) {
   91 |         productService.delete(id);
   92 |         return Result.ok();
   93 |     }
   94 | }
```
