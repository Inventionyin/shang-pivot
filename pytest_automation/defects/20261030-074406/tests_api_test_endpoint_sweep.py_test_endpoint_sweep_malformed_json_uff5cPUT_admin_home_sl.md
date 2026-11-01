# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPUT /admin/home/slots/{code}]-bba0b3fb

- 运行：20261030-074406（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPUT /admin/home/slots/{code}] 异常-畸形 JSON 请求体｜PUT /admin/home/slots/{code}
- 接口：PUT /admin/home/slots/{code}
- 分类：异常｜优先级：P2
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: malformed_json PUT /admin/home/slots/{code}：HTTP 500
assert 500 < 500
 +  where 500 = <Response [500]>.status_code

## 请求 / 响应

```json
{
  "request": {
    "query": null,
    "body": null,
    "raw": "{\"broken\":"
  },
  "response": {
    "status": 500,
    "body": {
      "code": 50000,
      "msg": "系统异常",
      "traceId": "7bcc3e934b5a441fbb6b53548f6b39eb"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/AdminOpsController.java:56（endpoint）

```java
   51 |     }
   52 | 
   53 |     @PutMapping("/admin/home/slots/{code}")
   54 |     @OpLog(module = "content", action = "home")
   55 |     @Operation(summary = "保存运营位")
   56 |     public Result<Map<String, Object>> saveSlot(@PathVariable String code,
   57 |                                                 @RequestBody HomeSlotService.SlotSaveReq req) {
   58 |         return Result.ok(homeSlotService.saveByCode(code, req));
   59 |     }
   60 | 
   61 |     @GetMapping("/admin/reviews")
```
