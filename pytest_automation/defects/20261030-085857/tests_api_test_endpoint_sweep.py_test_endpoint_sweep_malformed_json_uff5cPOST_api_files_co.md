# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /api/files/confirm]-093c48a7

- 运行：20261030-085857（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /api/files/confirm] 异常-畸形 JSON 请求体｜POST /api/files/confirm
- 接口：POST /api/files/confirm
- 分类：异常｜优先级：P2
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: malformed_json POST /api/files/confirm：HTTP 500
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
      "traceId": "8757dea9b84740f98d9aec9454b24a13"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=1f116b2af2ee45a89decace98c1899cc 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/CustomerFileController.java:117（endpoint）

```java
  112 |         data.put("headers", result.headers());
  113 |         return Result.ok(data);
  114 |     }
  115 | 
  116 |     @PostMapping("/confirm")
  117 |     public Result<Map<String, Object>> confirm(@RequestBody ConfirmReq req) {
  118 |         Long customerId = SecurityContexts.requireUserId();
  119 |         if (!StringUtils.hasText(req.getObjectKey())) {
  120 |             throw new BizException(ErrorCode.BAD_REQUEST, "objectKey不能为空");
  121 |         }
  122 |         String biz = normalizeBiz(req.getBizType());
```
