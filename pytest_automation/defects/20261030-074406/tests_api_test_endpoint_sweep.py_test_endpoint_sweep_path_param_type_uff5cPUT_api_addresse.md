# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /api/addresses/{id}/default]-4cb9b23d

- 运行：20261030-074406（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /api/addresses/{id}/default] 逆向-路径参数类型错误｜PUT /api/addresses/{id}/default
- 接口：PUT /api/addresses/{id}/default
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type PUT /api/addresses/{id}/default：HTTP 500
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
      "traceId": "0a6ebc7492414c84a348bcc59f708fc9"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/StorefrontController.java:149（endpoint）

```java
  144 |         addressService.delete(id);
  145 |         return Result.ok();
  146 |     }
  147 | 
  148 |     @PutMapping("/api/addresses/{id}/default")
  149 |     public Result<Void> defaultAddress(@PathVariable Long id) {
  150 |         addressService.setDefault(id);
  151 |         return Result.ok();
  152 |     }
  153 | 
  154 |     @GetMapping("/api/favorites")
```
