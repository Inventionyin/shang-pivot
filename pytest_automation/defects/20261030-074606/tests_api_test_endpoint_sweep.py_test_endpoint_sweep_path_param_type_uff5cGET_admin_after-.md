# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cGET /admin/after-sales/{id}]-e13f955f

- 运行：20261030-074606（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cGET /admin/after-sales/{id}] 逆向-路径参数类型错误｜GET /admin/after-sales/{id}
- 接口：GET /admin/after-sales/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type GET /admin/after-sales/{id}：HTTP 500
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
      "traceId": "0790c78f967e436db59c583fcbeffa70"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/AfterSaleController.java:40（endpoint）

```java
   35 |         return Result.ok(afterSaleService.page(page, size, status, type, afterSaleNo));
   36 |     }
   37 | 
   38 |     @GetMapping("/{id}")
   39 |     @Operation(summary = "获取售后详情", description = "返回售后单信息及关联订单")
   40 |     public Result<Map<String, Object>> detail(
   41 |             @Parameter(description = "售后单ID", example = "1") @PathVariable Long id) {
   42 |         return Result.ok(afterSaleService.detail(id));
   43 |     }
   44 | 
   45 |     @PostMapping("/{id}/audit")
```
