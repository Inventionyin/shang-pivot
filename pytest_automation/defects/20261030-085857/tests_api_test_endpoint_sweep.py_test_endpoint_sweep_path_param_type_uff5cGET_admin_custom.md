# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cGET /admin/customers/{id}]-19f0d60b

- 运行：20261030-085857（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cGET /admin/customers/{id}] 逆向-路径参数类型错误｜GET /admin/customers/{id}
- 接口：GET /admin/customers/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type GET /admin/customers/{id}：HTTP 500
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
      "traceId": "d3ce29efa34241c581caac8659182442"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=802c0cec54ab400d83d720dd77629c32 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/CustomerAdminController.java:37（endpoint）

```java
   32 |         return Result.ok(customerAdminService.page(page, size, phone, nickname, status, clientType));
   33 |     }
   34 | 
   35 |     @GetMapping("/{id}")
   36 |     @Operation(summary = "会员详情")
   37 |     public Result<Map<String, Object>> detail(
   38 |             @Parameter(description = "会员ID") @PathVariable Long id) {
   39 |         return Result.ok(customerAdminService.detail(id));
   40 |     }
   41 | 
   42 |     @PutMapping("/{id}/status")
```
