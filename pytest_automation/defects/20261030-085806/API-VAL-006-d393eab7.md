# 缺陷 API-VAL-006-d393eab7

- 运行：20261030-085806（git b13bf61）
- 用例：API-VAL-006 API-VAL-006 收货人姓名 65 字符（超列宽）应被拒
- 接口：POST /api/addresses
- 分类：边界值｜优先级：P0
- 断言意图：边界值-非法：预期 400（缺陷 D-06：实际 500）
- 失败摘要：AssertionError: 业务码期望 40000 实际 50000；msg=系统异常

## 请求 / 响应

```json
{
  "request": {
    "query": null,
    "body": {
      "name": "测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测",
      "phone": "139****3221",
      "province": "北京市",
      "city": "北京市",
      "district": "朝阳区",
      "detail": "自动化测试地址 c9328d",
      "isDefault": true
    },
    "raw": null
  },
  "response": {
    "status": 500,
    "body": {
      "code": 50000,
      "msg": "系统异常",
      "traceId": "cf9203bcefca48efab9058137265aaa4"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=30a5aee97f754a668f7c8d264bdc05c0 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/StorefrontController.java:132（endpoint）

```java
  127 |     public Result<List<?>> addresses() {
  128 |         return Result.ok(addressService.list());
  129 |     }
  130 | 
  131 |     @PostMapping("/api/addresses")
  132 |     public Result<Long> createAddress(@RequestBody AddressService.AddressReq req) {
  133 |         return Result.ok(addressService.create(req));
  134 |     }
  135 | 
  136 |     @PutMapping("/api/addresses/{id}")
  137 |     public Result<Void> updateAddress(@PathVariable Long id, @RequestBody AddressService.AddressReq req) {
```
