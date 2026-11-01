# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/reviews/{id}/status]-8bfdd430

- 运行：20261030-074606（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/reviews/{id}/status] 逆向-路径参数类型错误｜PUT /admin/reviews/{id}/status
- 接口：PUT /admin/reviews/{id}/status
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type PUT /admin/reviews/{id}/status：HTTP 500
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
      "traceId": "ee7c5c8648694c588005cc71464190ee"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/AdminOpsController.java:76（endpoint）

```java
   71 |     }
   72 | 
   73 |     @PutMapping("/admin/reviews/{id}/status")
   74 |     @OpLog(module = "review", action = "status")
   75 |     @Operation(summary = "隐藏/恢复评价")
   76 |     public Result<Void> reviewStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
   77 |         reviewAdminService.updateStatus(id, body.get("status"));
   78 |         return Result.ok();
   79 |     }
   80 | 
   81 |     @GetMapping("/admin/in-out")
```
