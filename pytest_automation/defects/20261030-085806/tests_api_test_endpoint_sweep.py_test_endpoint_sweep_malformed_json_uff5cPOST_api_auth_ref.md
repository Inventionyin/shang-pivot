# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /api/auth/refresh]-a1adb0b1

- 运行：20261030-085806（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /api/auth/refresh] 异常-畸形 JSON 请求体｜POST /api/auth/refresh
- 接口：POST /api/auth/refresh
- 分类：异常｜优先级：P2
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: malformed_json POST /api/auth/refresh：HTTP 500
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
      "traceId": "7fc6ba0de8d34dbbbf7c2f2c39550d63"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=9dd5321c88e24a3cab5feb8985f10be4 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/CustomerAuthController.java:41（endpoint）

```java
   36 |         return Result.ok(customerAuthService.login(req));
   37 |     }
   38 | 
   39 |     @PostMapping("/api/auth/refresh")
   40 |     @Operation(summary = "刷新令牌")
   41 |     public Result<CustomerLoginVO> refresh(@Valid @RequestBody RefreshRequest req) {
   42 |         return Result.ok(customerAuthService.refresh(req.getRefreshToken()));
   43 |     }
   44 | 
   45 |     @PostMapping("/api/auth/logout")
   46 |     @Operation(summary = "退出登录")
```
