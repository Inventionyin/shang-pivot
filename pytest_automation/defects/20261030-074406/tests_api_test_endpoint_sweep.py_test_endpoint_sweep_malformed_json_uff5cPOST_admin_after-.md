# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/after-sales/{id}/audit]-786f1b03

- 运行：20261030-074406（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/after-sales/{id}/audit] 异常-畸形 JSON 请求体｜POST /admin/after-sales/{id}/audit
- 接口：POST /admin/after-sales/{id}/audit
- 分类：异常｜优先级：P2
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: malformed_json POST /admin/after-sales/{id}/audit：HTTP 500
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
      "traceId": "1ba0a3dceec0403696eb3b13fdbf1cb4"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/AfterSaleController.java:48（endpoint）

```java
   43 |     }
   44 | 
   45 |     @PostMapping("/{id}/audit")
   46 |     @OpLog(module = "aftersale", action = "audit")
   47 |     @Operation(summary = "审核售后单", description = "对待审核售后单进行通过或驳回")
   48 |     public Result<Void> audit(
   49 |             @Parameter(description = "售后单ID", example = "1") @PathVariable Long id,
   50 |             @RequestBody AfterSaleService.AuditReq req) {
   51 |         afterSaleService.audit(id, req);
   52 |         return Result.ok();
   53 |     }
```
