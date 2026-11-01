# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/warehouses]-1a34ae04

- 运行：20261030-085857（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/warehouses] 异常-畸形 JSON 请求体｜POST /admin/warehouses
- 接口：POST /admin/warehouses
- 分类：异常｜优先级：P2
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: malformed_json POST /admin/warehouses：HTTP 500
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
      "traceId": "079886a97fa34ded8b03ee1c4ed61c6c"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=5a3fb93ace264c5390b677148aa927c1 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/WarehouseController.java:35（endpoint）

```java
   30 |     }
   31 | 
   32 |     @PostMapping
   33 |     @OpLog(module = "warehouse", action = "create")
   34 |     @Operation(summary = "创建仓库", description = "新增仓库，未传 status 时默认为 ENABLED")
   35 |     public Result<Long> create(@RequestBody Warehouse w) {
   36 |         if (!StringUtils.hasText(w.getName())) {
   37 |             throw new BizException(ErrorCode.BAD_REQUEST, "请填写仓库名称");
   38 |         }
   39 |         if (!StringUtils.hasText(w.getStatus())) {
   40 |             w.setStatus("ENABLED");
```
