# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cGET /api/products/{spuId}/reviews]-49ef0a38

- 运行：20261030-085806（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cGET /api/products/{spuId}/reviews] 逆向-路径参数类型错误｜GET /api/products/{spuId}/reviews
- 接口：GET /api/products/{spuId}/reviews
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type GET /api/products/{spuId}/reviews：HTTP 500
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
      "traceId": "025ada5143604842a561e8691c271490"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=d23b5ba24d9f4d618e14d1ca061f04af 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/StorefrontController.java:305（endpoint）

```java
  300 |         reviewMapper.insert(review);
  301 |         return Result.ok(review.getId());
  302 |     }
  303 | 
  304 |     @GetMapping("/api/products/{spuId}/reviews")
  305 |     public Result<PageResult<Review>> productReviews(
  306 |             @PathVariable Long spuId,
  307 |             @RequestParam(defaultValue = "1") int page,
  308 |             @RequestParam(defaultValue = "10") int size) {
  309 |         Page<Review> p = reviewMapper.selectPage(new Page<>(page, size), new LambdaQueryWrapper<Review>()
  310 |                 .eq(Review::getSpuId, spuId)
```
