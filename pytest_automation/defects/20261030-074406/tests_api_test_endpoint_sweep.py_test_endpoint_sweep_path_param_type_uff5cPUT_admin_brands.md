# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/brands/{id}]-97ed2581

- 运行：20261030-074406（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/brands/{id}] 逆向-路径参数类型错误｜PUT /admin/brands/{id}
- 接口：PUT /admin/brands/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type PUT /admin/brands/{id}：HTTP 500
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
      "traceId": "96bfa8c78225420fa8c184e8f9b278f3"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/BrandController.java:60（endpoint）

```java
   55 |     }
   56 | 
   57 |     @PutMapping("/{id}")
   58 |     @OpLog(module = "brand", action = "update")
   59 |     @Operation(summary = "更新品牌", description = "修改品牌名称、Logo、排序及状态")
   60 |     public Result<Void> update(
   61 |             @Parameter(description = "品牌ID", example = "1") @PathVariable Long id,
   62 |             @RequestBody Brand patch) {
   63 |         Brand brand = brandMapper.selectById(id);
   64 |         if (brand == null) throw new BizException(ErrorCode.NOT_FOUND);
   65 |         brand.setName(patch.getName());
```
