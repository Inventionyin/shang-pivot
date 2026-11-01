# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cDELETE /admin/dicts/{id}]-fa131bab

- 运行：20261030-085806（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cDELETE /admin/dicts/{id}] 逆向-路径参数类型错误｜DELETE /admin/dicts/{id}
- 接口：DELETE /admin/dicts/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type DELETE /admin/dicts/{id}：HTTP 500
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
      "traceId": "f2d7275d5be44cda9382647cea74f347"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=886677f348e3401e8473a4b4a754051e 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/DictController.java:82（endpoint）

```java
   77 |     }
   78 | 
   79 |     @DeleteMapping("/admin/dicts/{id}")
   80 |     @OpLog(module = "dict", action = "delete")
   81 |     @Operation(summary = "删除字典", description = "删除字典及其所有字典项")
   82 |     public Result<Void> deleteDict(
   83 |             @Parameter(description = "字典ID", example = "1") @PathVariable Long id) {
   84 |         dictItemMapper.delete(new LambdaQueryWrapper<DictItem>().eq(DictItem::getDictId, id));
   85 |         dictMapper.deleteById(id);
   86 |         return Result.ok();
   87 |     }
```
