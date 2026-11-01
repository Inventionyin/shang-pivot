# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /api/auth/sms/send]-240d6a76

- 运行：20261030-085806（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /api/auth/sms/send] 异常-畸形 JSON 请求体｜POST /api/auth/sms/send
- 接口：POST /api/auth/sms/send
- 分类：异常｜优先级：P2
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: malformed_json POST /api/auth/sms/send：HTTP 500
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
      "traceId": "90334dbf0f63461cb468e40dc790a966"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=9dd5321c88e24a3cab5feb8985f10be4 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/CustomerAuthController.java:29（endpoint）

```java
   24 | 
   25 |     private final CustomerAuthService customerAuthService;
   26 | 
   27 |     @PostMapping("/api/auth/sms/send")
   28 |     @Operation(summary = "发送验证码")
   29 |     public Result<Map<String, Object>> sendSms(@Valid @RequestBody SmsSendRequest req) {
   30 |         return Result.ok(customerAuthService.sendSms(req.getPhone()));
   31 |     }
   32 | 
   33 |     @PostMapping({"/api/auth/login", "/api/auth/phone"})
   34 |     @Operation(summary = "手机号验证码登录")
```
