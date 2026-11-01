# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/dict-items/{id}]-e7824f41

- 运行：20261030-074406（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/dict-items/{id}] 逆向-路径参数类型错误｜PUT /admin/dict-items/{id}
- 接口：PUT /admin/dict-items/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type PUT /admin/dict-items/{id}：HTTP 500
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
      "traceId": "95754145ba3c4c0b98dff8bec4a0102e"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/DictController.java:135（endpoint）

```java
  130 |     }
  131 | 
  132 |     @PutMapping("/admin/dict-items/{id}")
  133 |     @OpLog(module = "dict", action = "updateItem")
  134 |     @Operation(summary = "更新字典项", description = "修改字典项的标签、值、排序等信息")
  135 |     public Result<Void> updateItem(
  136 |             @Parameter(description = "字典项ID", example = "1") @PathVariable Long id,
  137 |             @RequestBody DictItem patch) {
  138 |         DictItem item = dictItemMapper.selectById(id);
  139 |         if (item == null) {
  140 |             throw new BizException(ErrorCode.NOT_FOUND);
```
