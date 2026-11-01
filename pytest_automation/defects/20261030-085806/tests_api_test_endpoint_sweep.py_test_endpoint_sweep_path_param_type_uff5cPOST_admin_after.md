# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPOST /admin/after-sales/{id}/audit]-43abea64

- 运行：20261030-085806（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPOST /admin/after-sales/{id}/audit] 逆向-路径参数类型错误｜POST /admin/after-sales/{id}/audit
- 接口：POST /admin/after-sales/{id}/audit
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type POST /admin/after-sales/{id}/audit：HTTP 500
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
      "traceId": "3c0db977a01b4946986b0958fafadb94"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=929e3b124fe94fd1a565ea17f907a4ac 的日志（BACKEND_LOG 未配置或日志已轮转）
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
