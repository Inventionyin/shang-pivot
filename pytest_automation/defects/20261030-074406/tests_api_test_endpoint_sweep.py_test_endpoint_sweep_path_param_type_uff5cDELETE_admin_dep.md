# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cDELETE /admin/depts/{id}]-4d8885bc

- 运行：20261030-074406（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cDELETE /admin/depts/{id}] 逆向-路径参数类型错误｜DELETE /admin/depts/{id}
- 接口：DELETE /admin/depts/{id}
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: path_param_type DELETE /admin/depts/{id}：HTTP 500
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
      "traceId": "503e7007180a4255b7f11b7c219c002e"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/DeptController.java:90（endpoint）

```java
   85 |     }
   86 | 
   87 |     @DeleteMapping("/{id}")
   88 |     @OpLog(module = "dept", action = "delete")
   89 |     @Operation(summary = "删除部门", description = "删除部门节点；存在子部门或用户引用时不允许删除")
   90 |     public Result<Void> delete(
   91 |             @Parameter(description = "部门ID", example = "1") @PathVariable Long id) {
   92 |         Long children = deptMapper.selectCount(new LambdaQueryWrapper<Dept>().eq(Dept::getParentId, id));
   93 |         if (children != null && children > 0) {
   94 |             throw new BizException(ErrorCode.CONFLICT, "存在子部门，无法删除");
   95 |         }
```
