# 缺陷 API-VAL-008-5acbff6f

- 运行：20261030-085857（git b13bf61）
- 用例：API-VAL-008 API-VAL-008 详细地址 256 字符（超列宽）应被拒
- 接口：POST /api/addresses
- 分类：边界值｜优先级：P0
- 断言意图：边界值-非法：预期 400（缺陷 D-07：实际 500）
- 失败摘要：AssertionError: 业务码期望 40000 实际 50000；msg=系统异常

## 请求 / 响应

```json
{
  "request": {
    "query": null,
    "body": {
      "name": "自动化测试",
      "phone": "139****4653",
      "province": "北京市",
      "city": "北京市",
      "district": "朝阳区",
      "detail": "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx...",
      "isDefault": true
    },
    "raw": null
  },
  "response": {
    "status": 500,
    "body": {
      "code": 50000,
      "msg": "系统异常",
      "traceId": "a95d3c397cc54a0a9b3c813f73c45771"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=3db6a35830784601bfa35ccf7f632e30 的日志（BACKEND_LOG 未配置或日志已轮转）
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
