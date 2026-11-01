# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/users/{id}/status]-49a5c469

- 运行：20261030-074606（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/users/{id}/status] 逆向-路径参数类型错误｜PUT /admin/users/{id}/status
- 接口：PUT /admin/users/{id}/status
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type PUT /admin/users/{id}/status：HTTP 500
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
      "traceId": "12f06b19c6934a36bb2be968021b437f"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/AdminUserController.java:66（endpoint）

```java
   61 |     }
   62 | 
   63 |     @PutMapping("/{id}/status")
   64 |     @OpLog(module = "user", action = "updateStatus")
   65 |     @Operation(summary = "更新用户状态", description = "启用或禁用用户")
   66 |     public Result<Void> status(
   67 |             @Parameter(description = "用户ID", example = "1") @PathVariable Long id,
   68 |             @io.swagger.v3.oas.annotations.parameters.RequestBody(
   69 |                     description = "状态更新",
   70 |                     content = @Content(schema = @Schema(example = "{\"status\":\"ENABLED\"}")))
   71 |             @RequestBody Map<String, String> body) {
```
