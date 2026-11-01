# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/categories]-7bdcd665

- 运行：20261030-085806（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/categories] 异常-畸形 JSON 请求体｜POST /admin/categories
- 接口：POST /admin/categories
- 分类：异常｜优先级：P2
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: malformed_json POST /admin/categories：HTTP 500
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
      "traceId": "87212d83f35f4943a192585ba724e498"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=f4e9a650838a4aaf97987a736cfcab65 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/CategoryController.java:55（endpoint）

```java
   50 |     }
   51 | 
   52 |     @PostMapping
   53 |     @OpLog(module = "category", action = "create")
   54 |     @Operation(summary = "创建类目", description = "新增商品类目，未传 parentId 时默认为顶级类目")
   55 |     public Result<Long> create(@RequestBody Category c) {
   56 |         if (!StringUtils.hasText(c.getName())) {
   57 |             throw new BizException(ErrorCode.BAD_REQUEST, "请填写类目名称");
   58 |         }
   59 |         if (c.getParentId() == null) c.setParentId(0L);
   60 |         if (c.getSort() == null) c.setSort(0);
```
