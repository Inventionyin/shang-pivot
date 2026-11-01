# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/depts]-4ab540a3

- 运行：20261030-074606（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/depts] 异常-畸形 JSON 请求体｜POST /admin/depts
- 接口：POST /admin/depts
- 分类：异常｜优先级：P2
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: malformed_json POST /admin/depts：HTTP 500
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
      "traceId": "2c41beec3d41433394195e7dcdd61d8e"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/DeptController.java:52（endpoint）

```java
   47 |     }
   48 | 
   49 |     @PostMapping
   50 |     @OpLog(module = "dept", action = "create")
   51 |     @Operation(summary = "创建部门", description = "新增组织部门节点")
   52 |     public Result<Long> create(@RequestBody Dept dept) {
   53 |         if (!StringUtils.hasText(dept.getName())) {
   54 |             throw new BizException(ErrorCode.BAD_REQUEST, "请填写部门名称");
   55 |         }
   56 |         if (dept.getParentId() == null) {
   57 |             dept.setParentId(0L);
```
