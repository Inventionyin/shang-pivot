# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/files/confirm]-8b6de583

- 运行：20261030-074406（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/files/confirm] 异常-畸形 JSON 请求体｜POST /admin/files/confirm
- 接口：POST /admin/files/confirm
- 分类：异常｜优先级：P2
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: malformed_json POST /admin/files/confirm：HTTP 500
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
      "traceId": "8a1a790ffe8840edaf8fefa7cfeefda7"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/FileController.java:128（endpoint）

```java
  123 |     }
  124 | 
  125 |     @PostMapping("/confirm")
  126 |     @OpLog(module = "file", action = "confirm")
  127 |     @Operation(summary = "确认上传完成", description = "客户端直传成功后回调，登记文件元数据并返回访问地址")
  128 |     public Result<Map<String, Object>> confirm(@RequestBody ConfirmReq req) {
  129 |         if (!StringUtils.hasText(req.getObjectKey())) {
  130 |             throw new BizException(ErrorCode.BAD_REQUEST, "objectKey不能为空");
  131 |         }
  132 |         FileObject fo = new FileObject();
  133 |         fo.setBucket(ossProperties.getBucketName());
```
