# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/permissions/{id}]-fbb33df6

- 运行：20261030-085806（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/permissions/{id}] 逆向-路径参数类型错误｜PUT /admin/permissions/{id}
- 接口：PUT /admin/permissions/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type PUT /admin/permissions/{id}：HTTP 500
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
      "traceId": "3f28d1e590c14d198a297641119f6ac9"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=789144cda5c04c669d8f2a2017c76104 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/PermissionController.java:82（endpoint）

```java
   77 |     }
   78 | 
   79 |     @PutMapping("/{id}")
   80 |     @OpLog(module = "permission", action = "update")
   81 |     @Operation(summary = "更新权限", description = "修改权限节点的名称、路径、图标等信息")
   82 |     public Result<Void> update(
   83 |             @Parameter(description = "权限ID", example = "1") @PathVariable Long id,
   84 |             @RequestBody Permission patch) {
   85 |         Permission p = permissionMapper.selectById(id);
   86 |         if (p == null) {
   87 |             throw new BizException(ErrorCode.NOT_FOUND);
```
