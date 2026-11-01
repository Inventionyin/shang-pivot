# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/warehouses/{id}]-db7e6a19

- 运行：20261030-085806（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/warehouses/{id}] 逆向-路径参数类型错误｜PUT /admin/warehouses/{id}
- 接口：PUT /admin/warehouses/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type PUT /admin/warehouses/{id}：HTTP 500
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
      "traceId": "e9c81d2b88a04e76a9ceb41046a9f1f4"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=079886a97fa34ded8b03ee1c4ed61c6c 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/WarehouseController.java:49（endpoint）

```java
   44 |     }
   45 | 
   46 |     @PutMapping("/{id}")
   47 |     @OpLog(module = "warehouse", action = "update")
   48 |     @Operation(summary = "更新仓库", description = "修改仓库名称及状态")
   49 |     public Result<Void> update(
   50 |             @Parameter(description = "仓库ID", example = "1") @PathVariable Long id,
   51 |             @RequestBody Warehouse patch) {
   52 |         Warehouse w = warehouseMapper.selectById(id);
   53 |         if (w == null) {
   54 |             throw new BizException(ErrorCode.NOT_FOUND);
```
