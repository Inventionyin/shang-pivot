# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cDELETE /api/addresses/{id}]-7a0d8159

- 运行：20261030-085806（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cDELETE /api/addresses/{id}] 逆向-路径参数类型错误｜DELETE /api/addresses/{id}
- 接口：DELETE /api/addresses/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type DELETE /api/addresses/{id}：HTTP 500
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
      "traceId": "978a33a543db42ad9b172c402afe4b35"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=e9c81d2b88a04e76a9ceb41046a9f1f4 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/StorefrontController.java:143（endpoint）

```java
  138 |         addressService.update(id, req);
  139 |         return Result.ok();
  140 |     }
  141 | 
  142 |     @DeleteMapping("/api/addresses/{id}")
  143 |     public Result<Void> deleteAddress(@PathVariable Long id) {
  144 |         addressService.delete(id);
  145 |         return Result.ok();
  146 |     }
  147 | 
  148 |     @PutMapping("/api/addresses/{id}/default")
```
