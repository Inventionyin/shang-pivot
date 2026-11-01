# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cDELETE /admin/categories/{id}]-19a299a3

- 运行：20261030-085806（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cDELETE /admin/categories/{id}] 逆向-路径参数类型错误｜DELETE /admin/categories/{id}
- 接口：DELETE /admin/categories/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type DELETE /admin/categories/{id}：HTTP 500
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
      "traceId": "d9ae5676a4324ee5a396adbf7f64826d"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=ac3101fbd668442ea832b40edddaf73f 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/CategoryController.java:86（endpoint）

```java
   81 |     }
   82 | 
   83 |     @DeleteMapping("/{id}")
   84 |     @OpLog(module = "category", action = "delete")
   85 |     @Operation(summary = "删除类目", description = "删除指定类目，存在子类目、商品或属性引用时不可删除")
   86 |     public Result<Void> delete(
   87 |             @Parameter(description = "类目ID", example = "1") @PathVariable Long id) {
   88 |         Long children = categoryMapper.selectCount(new LambdaQueryWrapper<Category>().eq(Category::getParentId, id));
   89 |         if (children != null && children > 0) {
   90 |             throw new BizException(ErrorCode.CONFLICT, "存在子类目，请先处理");
   91 |         }
```
