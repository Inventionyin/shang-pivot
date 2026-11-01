# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /api/auth/login]-05020639

- 运行：20261030-074606（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /api/auth/login] 异常-畸形 JSON 请求体｜POST /api/auth/login
- 接口：POST /api/auth/login
- 分类：异常｜优先级：P2
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: malformed_json POST /api/auth/login：HTTP 500
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
      "traceId": "77bcc51608e24f0b97d5ae92739c11f0"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/CustomerAuthController.java:35（endpoint）

```java
   30 |         return Result.ok(customerAuthService.sendSms(req.getPhone()));
   31 |     }
   32 | 
   33 |     @PostMapping({"/api/auth/login", "/api/auth/phone"})
   34 |     @Operation(summary = "手机号验证码登录")
   35 |     public Result<CustomerLoginVO> login(@Valid @RequestBody CustomerLoginRequest req) {
   36 |         return Result.ok(customerAuthService.login(req));
   37 |     }
   38 | 
   39 |     @PostMapping("/api/auth/refresh")
   40 |     @Operation(summary = "刷新令牌")
```
