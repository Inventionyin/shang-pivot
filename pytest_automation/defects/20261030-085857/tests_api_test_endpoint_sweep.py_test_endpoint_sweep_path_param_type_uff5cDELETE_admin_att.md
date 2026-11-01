# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cDELETE /admin/attributes/{id}]-de0f4db7

- 运行：20261030-085857（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cDELETE /admin/attributes/{id}] 逆向-路径参数类型错误｜DELETE /admin/attributes/{id}
- 接口：DELETE /admin/attributes/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type DELETE /admin/attributes/{id}：HTTP 500
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
      "traceId": "a76b3d97716e4427b8a4c825d6fd7968"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=7777ae50a3184d71802e67bab2d7481e 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/AttributeController.java:72（endpoint）

```java
   67 |     }
   68 | 
   69 |     @DeleteMapping("/{id}")
   70 |     @OpLog(module = "attribute", action = "delete")
   71 |     @Operation(summary = "删除属性", description = "根据属性ID删除属性项")
   72 |     public Result<Void> delete(
   73 |             @Parameter(description = "属性ID", example = "1") @PathVariable Long id) {
   74 |         attributeMapper.deleteById(id);
   75 |         return Result.ok();
   76 |     }
   77 | }
```
