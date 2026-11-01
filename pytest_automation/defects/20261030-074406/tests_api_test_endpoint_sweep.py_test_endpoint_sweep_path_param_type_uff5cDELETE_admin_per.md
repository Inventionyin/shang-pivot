# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cDELETE /admin/permissions/{id}]-9c069d42

- 运行：20261030-074406（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cDELETE /admin/permissions/{id}] 逆向-路径参数类型错误｜DELETE /admin/permissions/{id}
- 接口：DELETE /admin/permissions/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type DELETE /admin/permissions/{id}：HTTP 500
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
      "traceId": "73f855501ed446babe38a69d3cde445a"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/PermissionController.java:108（endpoint）

```java
  103 |     }
  104 | 
  105 |     @DeleteMapping("/{id}")
  106 |     @OpLog(module = "permission", action = "delete")
  107 |     @Operation(summary = "删除权限", description = "删除权限节点，存在子节点时不允许删除")
  108 |     public Result<Void> delete(
  109 |             @Parameter(description = "权限ID", example = "1") @PathVariable Long id) {
  110 |         Long children = permissionMapper.selectCount(new LambdaQueryWrapper<Permission>().eq(Permission::getParentId, id));
  111 |         if (children != null && children > 0) {
  112 |             throw new BizException(ErrorCode.CONFLICT, "存在子节点，无法删除");
  113 |         }
```
