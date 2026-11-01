# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPOST /api/orders/{id}/confirm]-ad920230

- 运行：20261030-074406（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPOST /api/orders/{id}/confirm] 逆向-路径参数类型错误｜POST /api/orders/{id}/confirm
- 接口：POST /api/orders/{id}/confirm
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type POST /api/orders/{id}/confirm：HTTP 500
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
      "traceId": "61a46f179d4645c1a5c4ef1cac3f3937"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/StorefrontController.java:226（endpoint）

```java
  221 |         orderSubmitService.cancel(id);
  222 |         return Result.ok();
  223 |     }
  224 | 
  225 |     @PostMapping("/api/orders/{id}/confirm")
  226 |     public Result<Void> confirm(@PathVariable Long id) {
  227 |         orderSubmitService.confirmReceive(id);
  228 |         return Result.ok();
  229 |     }
  230 | 
  231 |     @GetMapping("/api/orders/{id}/logistics")
```
