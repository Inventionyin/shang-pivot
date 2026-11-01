# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/dicts/{id}]-213db536

- 运行：20261030-074606（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/dicts/{id}] 逆向-路径参数类型错误｜PUT /admin/dicts/{id}
- 接口：PUT /admin/dicts/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type PUT /admin/dicts/{id}：HTTP 500
assert 500 < 500
 +  where 500 = <Response [500]>.status_code

## 请求 / 响应

```json
{
  "request": {
    "query": null,
    "body": {},
    "raw": null
  },
  "response": {
    "status": 500,
    "body": {
      "code": 50000,
      "msg": "系统异常",
      "traceId": "276806ee42d548998a05c268ce216b1a"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/DictController.java:66（endpoint）

```java
   61 |     }
   62 | 
   63 |     @PutMapping("/admin/dicts/{id}")
   64 |     @OpLog(module = "dict", action = "update")
   65 |     @Operation(summary = "更新字典", description = "修改字典名称与备注")
   66 |     public Result<Void> updateDict(
   67 |             @Parameter(description = "字典ID", example = "1") @PathVariable Long id,
   68 |             @RequestBody Dict patch) {
   69 |         Dict dict = dictMapper.selectById(id);
   70 |         if (dict == null) {
   71 |             throw new BizException(ErrorCode.NOT_FOUND);
```
