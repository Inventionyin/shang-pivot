# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/dict-items]-ee20349c

- 运行：20261030-085806（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/dict-items] 异常-畸形 JSON 请求体｜POST /admin/dict-items
- 接口：POST /admin/dict-items
- 分类：异常｜优先级：P2
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: malformed_json POST /admin/dict-items：HTTP 500
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
      "traceId": "610d816cc1e141f48a98bd5e571a5987"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=d1be6ac0902148388d4c8baac9eb90f9 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/DictController.java:112（endpoint）

```java
  107 |     }
  108 | 
  109 |     @PostMapping("/admin/dict-items")
  110 |     @OpLog(module = "dict", action = "createItem")
  111 |     @Operation(summary = "创建字典项", description = "为指定字典新增字典项")
  112 |     public Result<Long> createItem(@RequestBody DictItem item) {
  113 |         if (item.getDictId() == null) {
  114 |             throw new BizException(ErrorCode.BAD_REQUEST, "请选择所属字典");
  115 |         }
  116 |         if (!StringUtils.hasText(item.getLabel())) {
  117 |             throw new BizException(ErrorCode.BAD_REQUEST, "请填写字典项标签");
```
