# 缺陷 API-LINK-025-aa3ceb60

- 运行：20261030-085857（git b13bf61）
- 用例：API-LINK-025 API-LINK-025 加购缺 SKU 但数量合规
- 接口：POST /api/cart/items
- 分类：参数组合｜优先级：P0
- 断言意图：预期 400（缺陷 D-13：当前 500）
- 失败摘要：AssertionError: 业务码期望 40000 实际 50000；msg=系统异常

## 请求 / 响应

```json
{
  "request": {
    "query": null,
    "body": {
      "quantity": 1
    },
    "raw": null
  },
  "response": {
    "status": 500,
    "body": {
      "code": 50000,
      "msg": "系统异常",
      "traceId": "e16881e0bb234a3a929c14d07379520f"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=b8e2c68dee3242e48494c401a722fb59 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/StorefrontController.java:100（endpoint）

```java
   95 |     public Result<Map<String, Object>> cart() {
   96 |         return Result.ok(cartService.getCart());
   97 |     }
   98 | 
   99 |     @PostMapping("/api/cart/items")
  100 |     public Result<Map<String, Object>> addCart(@RequestBody Map<String, Object> body) {
  101 |         Long skuId = Long.valueOf(body.get("skuId").toString());
  102 |         int qty = Integer.parseInt(body.get("quantity").toString());
  103 |         return Result.ok(cartService.addItem(skuId, qty));
  104 |     }
  105 | 
```
