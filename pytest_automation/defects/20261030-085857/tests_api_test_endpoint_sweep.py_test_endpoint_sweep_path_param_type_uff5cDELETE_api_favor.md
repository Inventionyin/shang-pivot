# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cDELETE /api/favorites/{spuId}]-616ad984

- 运行：20261030-085857（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cDELETE /api/favorites/{spuId}] 逆向-路径参数类型错误｜DELETE /api/favorites/{spuId}
- 接口：DELETE /api/favorites/{spuId}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type DELETE /api/favorites/{spuId}：HTTP 500
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
      "traceId": "11c865d5b33640d4a4c14787fd91a3bc"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=17e43ffcc2c042e493f5869c4d1262f2 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/StorefrontController.java:168（endpoint）

```java
  163 |         favoriteBrowseService.addFavorite(spuId);
  164 |         return Result.ok();
  165 |     }
  166 | 
  167 |     @DeleteMapping("/api/favorites/{spuId}")
  168 |     public Result<Void> removeFavorite(@PathVariable Long spuId) {
  169 |         favoriteBrowseService.removeFavorite(spuId);
  170 |         return Result.ok();
  171 |     }
  172 | 
  173 |     @GetMapping("/api/history")
```
