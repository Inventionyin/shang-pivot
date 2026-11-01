# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/system-configs/{id}]-29703b1e

- 运行：20261030-074606（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/system-configs/{id}] 逆向-路径参数类型错误｜PUT /admin/system-configs/{id}
- 接口：PUT /admin/system-configs/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type PUT /admin/system-configs/{id}：HTTP 500
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
      "traceId": "6886446544a942789068d382a14fbd68"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/SystemConfigController.java:41（endpoint）

```java
   36 |     }
   37 | 
   38 |     @PutMapping("/{id}")
   39 |     @OpLog(module = "config", action = "update")
   40 |     @Operation(summary = "更新系统配置", description = "修改指定配置项的值与备注")
   41 |     public Result<Void> update(
   42 |             @Parameter(description = "配置ID", example = "1") @PathVariable Long id,
   43 |             @RequestBody SystemConfig patch) {
   44 |         SystemConfig cfg = systemConfigMapper.selectById(id);
   45 |         if (cfg == null) {
   46 |             throw new BizException(ErrorCode.NOT_FOUND);
```
