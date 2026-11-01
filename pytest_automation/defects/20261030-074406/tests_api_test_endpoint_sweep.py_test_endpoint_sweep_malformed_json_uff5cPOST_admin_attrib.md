# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/attributes]-119efbda

- 运行：20261030-074406（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/attributes] 异常-畸形 JSON 请求体｜POST /admin/attributes
- 接口：POST /admin/attributes
- 分类：异常｜优先级：P2
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: malformed_json POST /admin/attributes：HTTP 500
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
      "traceId": "7777ae50a3184d71802e67bab2d7481e"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/AttributeController.java:38（endpoint）

```java
   33 |     }
   34 | 
   35 |     @PostMapping
   36 |     @OpLog(module = "attribute", action = "create")
   37 |     @Operation(summary = "创建属性", description = "新增商品属性项，未传 type 时默认为 SPEC")
   38 |     public Result<Long> create(@RequestBody Attribute attr) {
   39 |         // commerce.attribute.category_id / name 为 NOT NULL，缺失时应在入口拒绝而非落库报错
   40 |         if (attr.getCategoryId() == null) {
   41 |             throw new BizException(ErrorCode.BAD_REQUEST, "请选择类目");
   42 |         }
   43 |         if (!StringUtils.hasText(attr.getName())) {
```
