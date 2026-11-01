# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/brands]-b80055d8

- 运行：20261030-074606（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/brands] 异常-畸形 JSON 请求体｜POST /admin/brands
- 接口：POST /admin/brands
- 分类：异常｜优先级：P2
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: malformed_json POST /admin/brands：HTTP 500
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
      "traceId": "050fa106a91a447c9788abf7be8a9ec9"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/BrandController.java:44（endpoint）

```java
   39 |     }
   40 | 
   41 |     @PostMapping
   42 |     @OpLog(module = "brand", action = "create")
   43 |     @Operation(summary = "创建品牌", description = "新增商品品牌")
   44 |     public Result<Long> create(@RequestBody Brand brand) {
   45 |         if (!StringUtils.hasText(brand.getName())) {
   46 |             throw new BizException(ErrorCode.BAD_REQUEST, "请填写品牌名称");
   47 |         }
   48 |         if (brand.getCategoryId() == null) {
   49 |             throw new BizException(ErrorCode.BAD_REQUEST, "请选择类目");
```
