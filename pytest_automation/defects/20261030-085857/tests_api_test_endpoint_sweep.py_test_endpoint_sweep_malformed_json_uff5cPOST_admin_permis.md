# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/permissions]-ee6f4c53

- 运行：20261030-085857（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/permissions] 异常-畸形 JSON 请求体｜POST /admin/permissions
- 接口：POST /admin/permissions
- 分类：异常｜优先级：P2
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: malformed_json POST /admin/permissions：HTTP 500
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
      "traceId": "5a770f69453f447baf6f3bfec6183c57"
    }
  }
}
```

## 后端日志（traceId）

```
未找到 traceId=06d3f69103c44987baaefa080fd0b2cb 的日志（BACKEND_LOG 未配置或日志已轮转）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/PermissionController.java:48（endpoint）

```java
   43 |     }
   44 | 
   45 |     @PostMapping
   46 |     @OpLog(module = "permission", action = "create")
   47 |     @Operation(summary = "创建权限", description = "新增菜单、目录或按钮权限节点")
   48 |     public Result<Long> create(@RequestBody Permission permission) {
   49 |         // identity.permission.code / name / type 为 NOT NULL；缺失时应返回参数错误而非落库 500
   50 |         if (!StringUtils.hasText(permission.getCode())) {
   51 |             throw new BizException(ErrorCode.BAD_REQUEST, "请填写权限编码");
   52 |         }
   53 |         if (!StringUtils.hasText(permission.getName())) {
```
