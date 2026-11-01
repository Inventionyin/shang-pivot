# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cGET /admin/operation-logs/{id}]-e951d09a

- 运行：20261030-074406（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cGET /admin/operation-logs/{id}] 逆向-路径参数类型错误｜GET /admin/operation-logs/{id}
- 接口：GET /admin/operation-logs/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type GET /admin/operation-logs/{id}：HTTP 500
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
      "traceId": "8a514dbb9e3a49a3a557759af9c0e67f"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/OperationLogController.java:111（endpoint）

```java
  106 |         }
  107 |     }
  108 | 
  109 |     @GetMapping("/{id}")
  110 |     @Operation(summary = "操作日志详情", description = "根据日志 ID 查看完整审计信息（含参数、UA、TraceId）")
  111 |     public Result<OperationLog> detail(
  112 |             @Parameter(description = "日志ID", example = "1") @PathVariable Long id) {
  113 |         return Result.ok(operationLogService.getById(id));
  114 |     }
  115 | 
  116 |     private LambdaQueryWrapper<OperationLog> buildQuery(
```
