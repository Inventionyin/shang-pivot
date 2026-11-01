# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cDELETE /admin/users/{id}]-0229f462

- 运行：20261030-074606（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cDELETE /admin/users/{id}] 逆向-路径参数类型错误｜DELETE /admin/users/{id}
- 接口：DELETE /admin/users/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type DELETE /admin/users/{id}：HTTP 500
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
      "traceId": "566f5d9037ec456d94b14e19631abed4"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/AdminUserController.java:92（endpoint）

```java
   87 |     }
   88 | 
   89 |     @DeleteMapping("/{id}")
   90 |     @OpLog(module = "user", action = "delete")
   91 |     @Operation(summary = "删除用户", description = "根据用户ID删除用户")
   92 |     public Result<Void> delete(
   93 |             @Parameter(description = "用户ID", example = "1") @PathVariable Long id) {
   94 |         adminUserService.delete(id);
   95 |         return Result.ok();
   96 |     }
   97 | 
```
