# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/depts/{id}]-48e0d890

- 运行：20261030-085857（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cPUT /admin/depts/{id}] 逆向-路径参数类型错误｜PUT /admin/depts/{id}
- 接口：PUT /admin/depts/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type PUT /admin/depts/{id}：HTTP 500
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
      "traceId": "c25835deb9644a94ac5c6ce6e5561783"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=69f15677c9de413586b90a40fc42a774 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/DeptController.java:72（endpoint）

```java
   67 |     }
   68 | 
   69 |     @PutMapping("/{id}")
   70 |     @OpLog(module = "dept", action = "update")
   71 |     @Operation(summary = "更新部门", description = "修改部门名称、上级部门、排序等信息")
   72 |     public Result<Void> update(
   73 |             @Parameter(description = "部门ID", example = "1") @PathVariable Long id,
   74 |             @RequestBody Dept patch) {
   75 |         Dept dept = deptMapper.selectById(id);
   76 |         if (dept == null) {
   77 |             throw new BizException(ErrorCode.NOT_FOUND);
```
