# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/attributes/{id}]-c2dad947

- 运行：20261030-074606（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/attributes/{id}] 逆向-路径参数类型错误｜PUT /admin/attributes/{id}
- 接口：PUT /admin/attributes/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type PUT /admin/attributes/{id}：HTTP 500
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
      "traceId": "a1e79310102f447ba5ecf939f3fe01c4"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/AttributeController.java:55（endpoint）

```java
   50 |     }
   51 | 
   52 |     @PutMapping("/{id}")
   53 |     @OpLog(module = "attribute", action = "update")
   54 |     @Operation(summary = "更新属性", description = "修改属性名称、类型、选项、排序及所属类目")
   55 |     public Result<Void> update(
   56 |             @Parameter(description = "属性ID", example = "1") @PathVariable Long id,
   57 |             @RequestBody Attribute patch) {
   58 |         Attribute attr = attributeMapper.selectById(id);
   59 |         if (attr == null) throw new BizException(ErrorCode.NOT_FOUND);
   60 |         attr.setName(patch.getName());
```
