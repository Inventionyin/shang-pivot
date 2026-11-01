# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[list_boundary\uff5cGET /admin/dict-items/by-code]-d474c469

- 运行：20261030-085857（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[list_boundary\uff5cGET /admin/dict-items/by-code] 边界-分页零值/负值/超大值｜GET /admin/dict-items/by-code
- 接口：GET /admin/dict-items/by-code
- 分类：边界值｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: list_boundary GET /admin/dict-items/by-code {'page': 0, 'size': 0}：HTTP 500
assert 500 < 500
 +  where 500 = <Response [500]>.status_code

## 请求 / 响应

```json
{
  "request": {
    "query": {
      "page": 0,
      "size": 0
    },
    "body": null,
    "raw": null
  },
  "response": {
    "status": 500,
    "body": {
      "code": 50000,
      "msg": "系统异常",
      "traceId": "0d8bd245a6e74cd89502ce7aa2e35119"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=610d816cc1e141f48a98bd5e571a5987 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/DictController.java:100（endpoint）

```java
   95 |                 .orderByAsc(DictItem::getSort)));
   96 |     }
   97 | 
   98 |     @GetMapping("/admin/dict-items/by-code")
   99 |     @Operation(summary = "按编码查询字典项", description = "根据字典编码查询字典项列表")
  100 |     public Result<List<DictItem>> itemsByCode(
  101 |             @Parameter(description = "字典编码", example = "user_status", required = true) @RequestParam String code) {
  102 |         Dict dict = dictMapper.selectOne(new LambdaQueryWrapper<Dict>().eq(Dict::getCode, code));
  103 |         if (dict == null) {
  104 |             return Result.ok(List.of());
  105 |         }
```
