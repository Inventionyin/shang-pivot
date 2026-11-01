# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/auth/login]-06c9ee43

- 运行：20261030-085857（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/auth/login] 异常-畸形 JSON 请求体｜POST /admin/auth/login
- 接口：POST /admin/auth/login
- 分类：异常｜优先级：P2
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: malformed_json POST /admin/auth/login：HTTP 500
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
      "traceId": "fad08772f9034da0baf6132f2889ebcd"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=ca526d151aae4f5db4a379de0c66e447 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/AuthController.java:35（endpoint）

```java
   30 | 
   31 |     private final AuthService authService;
   32 | 
   33 |     @PostMapping("/login")
   34 |     @Operation(summary = "用户登录", description = "使用用户名和密码登录，返回访问令牌与用户信息")
   35 |     public Result<LoginVO> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
   36 |         return Result.ok(authService.login(request, http.getRemoteAddr(), http.getHeader("User-Agent")));
   37 |     }
   38 | 
   39 |     @PostMapping("/logout")
   40 |     @Operation(summary = "退出登录", description = "使当前访问令牌失效")
```
