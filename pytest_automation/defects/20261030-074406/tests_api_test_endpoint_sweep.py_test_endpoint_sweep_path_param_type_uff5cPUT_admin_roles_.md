# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/roles/{id}]-3b0ce754

- 运行：20261030-074406（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/roles/{id}] 逆向-路径参数类型错误｜PUT /admin/roles/{id}
- 接口：PUT /admin/roles/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type PUT /admin/roles/{id}：HTTP 500
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
      "traceId": "fdb4f92805f5440599b3d9b06e02d241"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/RoleController.java:62（endpoint）

```java
   57 |     }
   58 | 
   59 |     @PutMapping("/{id}")
   60 |     @OpLog(module = "role", action = "update")
   61 |     @Operation(summary = "更新角色", description = "修改角色信息与权限分配")
   62 |     public Result<Void> update(
   63 |             @Parameter(description = "角色ID", example = "1") @PathVariable Long id,
   64 |             @RequestBody RoleSaveReq req) {
   65 |         Role role = new Role();
   66 |         role.setName(req.getName());
   67 |         role.setDataScope(req.getDataScope());
```
