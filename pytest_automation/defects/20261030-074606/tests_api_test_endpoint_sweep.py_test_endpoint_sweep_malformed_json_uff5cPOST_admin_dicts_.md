# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/dicts]-88c611fa

- 运行：20261030-074606（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/dicts] 异常-畸形 JSON 请求体｜POST /admin/dicts
- 接口：POST /admin/dicts
- 分类：异常｜优先级：P2
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: malformed_json POST /admin/dicts：HTTP 500
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
      "traceId": "886677f348e3401e8473a4b4a754051e"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/DictController.java:48（endpoint）

```java
   43 |     }
   44 | 
   45 |     @PostMapping("/admin/dicts")
   46 |     @OpLog(module = "dict", action = "create")
   47 |     @Operation(summary = "创建字典", description = "新增数据字典")
   48 |     public Result<Long> createDict(@RequestBody Dict dict) {
   49 |         if (!StringUtils.hasText(dict.getCode())) {
   50 |             throw new BizException(ErrorCode.BAD_REQUEST, "请填写字典编码");
   51 |         }
   52 |         if (!StringUtils.hasText(dict.getName())) {
   53 |             throw new BizException(ErrorCode.BAD_REQUEST, "请填写字典名称");
```
