# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/categories/{id}]-4c3df5c9

- 运行：20261030-085806（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/categories/{id}] 逆向-路径参数类型错误｜PUT /admin/categories/{id}
- 接口：PUT /admin/categories/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type PUT /admin/categories/{id}：HTTP 500
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
      "traceId": "8f7ff565b33e438a86bedb1f16b1df4e"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=13d58e25ed5f47228c68e24bdc8575ba 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/CategoryController.java:70（endpoint）

```java
   65 |     }
   66 | 
   67 |     @PutMapping("/{id}")
   68 |     @OpLog(module = "category", action = "update")
   69 |     @Operation(summary = "更新类目", description = "修改类目名称、层级、排序及状态")
   70 |     public Result<Void> update(
   71 |             @Parameter(description = "类目ID", example = "1") @PathVariable Long id,
   72 |             @RequestBody Category patch) {
   73 |         Category c = require(id);
   74 |         c.setName(patch.getName());
   75 |         c.setParentId(patch.getParentId());
```
