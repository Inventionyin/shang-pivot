# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cDELETE /admin/brands/{id}]-5d490089

- 运行：20261030-074406（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cDELETE /admin/brands/{id}] 逆向-路径参数类型错误｜DELETE /admin/brands/{id}
- 接口：DELETE /admin/brands/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type DELETE /admin/brands/{id}：HTTP 500
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
      "traceId": "5d5c7094a4e54cad834fc20f92387c06"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/BrandController.java:77（endpoint）

```java
   72 |     }
   73 | 
   74 |     @DeleteMapping("/{id}")
   75 |     @OpLog(module = "brand", action = "delete")
   76 |     @Operation(summary = "删除品牌", description = "根据品牌ID删除品牌；被商品引用时不可删除")
   77 |     public Result<Void> delete(
   78 |             @Parameter(description = "品牌ID", example = "1") @PathVariable Long id) {
   79 |         Long used = spuMapper.selectCount(new LambdaQueryWrapper<Spu>().eq(Spu::getBrandId, id));
   80 |         if (used != null && used > 0) {
   81 |             throw new BizException(ErrorCode.CONFLICT, "品牌已被商品引用，请先处理引用");
   82 |         }
```
