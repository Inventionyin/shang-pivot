# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /api/cart/items/{id}]-9d42d795

- 运行：20261030-074406（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /api/cart/items/{id}] 逆向-路径参数类型错误｜PUT /api/cart/items/{id}
- 接口：PUT /api/cart/items/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type PUT /api/cart/items/{id}：HTTP 500
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
      "traceId": "71bc05a243004c0284d5e2bbf27b571a"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/StorefrontController.java:107（endpoint）

```java
  102 |         int qty = Integer.parseInt(body.get("quantity").toString());
  103 |         return Result.ok(cartService.addItem(skuId, qty));
  104 |     }
  105 | 
  106 |     @PutMapping("/api/cart/items/{id}")
  107 |     public Result<Void> updateCart(@PathVariable Long id, @RequestBody Map<String, Object> body) {
  108 |         Integer qty = body.get("quantity") == null ? null : Integer.parseInt(body.get("quantity").toString());
  109 |         Boolean selected = body.get("selected") == null ? null : Boolean.valueOf(body.get("selected").toString());
  110 |         cartService.updateItem(id, qty, selected);
  111 |         return Result.ok();
  112 |     }
```
