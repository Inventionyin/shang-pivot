# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/auth/refresh]-18c11ef3

- 运行：20261030-085857（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/auth/refresh] 异常-畸形 JSON 请求体｜POST /admin/auth/refresh
- 接口：POST /admin/auth/refresh
- 分类：异常｜优先级：P2
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: malformed_json POST /admin/auth/refresh：HTTP 500
assert 500 < 500
 +  where 500 = <Response [500]>.status_code

## 请求 / 响应

```json
{
  "request": {
    "query": null,
    "body": null,
    "raw": "{\"broken\":"
  },
  "response": {
    "status": 500,
    "body": {
      "code": 50000,
      "msg": "系统异常",
      "traceId": "9252e201ba4f44c1914fefbd6583cda5"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=6eae2c8c27ba49b1ab21439d0ba4f98e 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/AuthController.java:50（endpoint）

```java
   45 |         return Result.ok();
   46 |     }
   47 | 
   48 |     @PostMapping("/refresh")
   49 |     @Operation(summary = "刷新令牌", description = "使用刷新令牌获取新的访问令牌")
   50 |     public Result<LoginVO> refresh(@Valid @RequestBody RefreshRequest request) {
   51 |         return Result.ok(authService.refresh(request.getRefreshToken()));
   52 |     }
   53 | 
   54 |     @GetMapping("/me")
   55 |     @Operation(summary = "获取当前用户信息", description = "返回当前登录用户的角色、权限与菜单")
```
