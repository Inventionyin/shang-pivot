# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cGET /admin/users/{id}]-3b7b487c

- 运行：20261030-074606（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cGET /admin/users/{id}] 逆向-路径参数类型错误｜GET /admin/users/{id}
- 接口：GET /admin/users/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type GET /admin/users/{id}：HTTP 500
assert 500 < 500
 +  where 500 = <Response [500]>.status_code

## 请求 / 响应

```json
{
  "request": {
    "query": {
      "page": 1,
      "size": 1
    },
    "body": null,
    "raw": null
  },
  "response": {
    "status": 500,
    "body": {
      "code": 50000,
      "msg": "系统异常",
      "traceId": "c6344e3a0d57459e90a2c3d9c962d248"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/AdminUserController.java:41（endpoint）

```java
   36 |         return Result.ok(adminUserService.page(page, size, username, phone, status, deptId));
   37 |     }
   38 | 
   39 |     @GetMapping("/{id}")
   40 |     @Operation(summary = "获取用户详情", description = "根据用户ID查询用户详情及关联角色")
   41 |     public Result<Map<String, Object>> get(
   42 |             @Parameter(description = "用户ID", example = "1") @PathVariable Long id) {
   43 |         return Result.ok(adminUserService.get(id));
   44 |     }
   45 | 
   46 |     @PostMapping
```
