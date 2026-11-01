# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cDELETE /admin/roles/{id}]-8180bb2a

- 运行：20261030-085857（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cDELETE /admin/roles/{id}] 逆向-路径参数类型错误｜DELETE /admin/roles/{id}
- 接口：DELETE /admin/roles/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type DELETE /admin/roles/{id}：HTTP 500
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
      "traceId": "1c61de6eed2c497390c8bd4bccf540b3"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=028f3f55143d4ddb831972ad87cec9d4 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/RoleController.java:77（endpoint）

```java
   72 |     }
   73 | 
   74 |     @DeleteMapping("/{id}")
   75 |     @OpLog(module = "role", action = "delete")
   76 |     @Operation(summary = "删除角色", description = "根据角色ID删除角色")
   77 |     public Result<Void> delete(
   78 |             @Parameter(description = "角色ID", example = "1") @PathVariable Long id) {
   79 |         roleService.delete(id);
   80 |         return Result.ok();
   81 |     }
   82 | 
```
