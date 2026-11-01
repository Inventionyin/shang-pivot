# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPUT /api/addresses/{id}]-95ec22ee

- 运行：20261030-074606（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPUT /api/addresses/{id}] 异常-畸形 JSON 请求体｜PUT /api/addresses/{id}
- 接口：PUT /api/addresses/{id}
- 分类：异常｜优先级：P2
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: malformed_json PUT /api/addresses/{id}：HTTP 500
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
      "traceId": "5a2fcf7e31fe41c5acd5ee3ada1957c2"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/StorefrontController.java:137（endpoint）

```java
  132 |     public Result<Long> createAddress(@RequestBody AddressService.AddressReq req) {
  133 |         return Result.ok(addressService.create(req));
  134 |     }
  135 | 
  136 |     @PutMapping("/api/addresses/{id}")
  137 |     public Result<Void> updateAddress(@PathVariable Long id, @RequestBody AddressService.AddressReq req) {
  138 |         addressService.update(id, req);
  139 |         return Result.ok();
  140 |     }
  141 | 
  142 |     @DeleteMapping("/api/addresses/{id}")
```
