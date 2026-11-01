# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/customers/{id}/status]-5792260b

- 运行：20261030-085806（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/customers/{id}/status] 逆向-路径参数类型错误｜PUT /admin/customers/{id}/status
- 接口：PUT /admin/customers/{id}/status
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type PUT /admin/customers/{id}/status：HTTP 500
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
      "traceId": "f0a24f90a2514ec7a7e22610633eed1c"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=0e75bb8df83c44fc91805f49430ed134 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/CustomerAdminController.java:45（endpoint）

```java
   40 |     }
   41 | 
   42 |     @PutMapping("/{id}/status")
   43 |     @OpLog(module = "customer", action = "status")
   44 |     @Operation(summary = "冻结/解冻会员")
   45 |     public Result<Void> updateStatus(
   46 |             @PathVariable Long id,
   47 |             @RequestBody Map<String, String> body) {
   48 |         customerAdminService.updateStatus(id, body.get("status"));
   49 |         return Result.ok();
   50 |     }
```
