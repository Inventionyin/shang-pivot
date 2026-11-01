# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[list_boundary\uff5cGET /admin/files/view]-29d13ae7

- 运行：20261030-074606（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[list_boundary\uff5cGET /admin/files/view] 边界-分页零值/负值/超大值｜GET /admin/files/view
- 接口：GET /admin/files/view
- 分类：边界值｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: list_boundary GET /admin/files/view {'page': 0, 'size': 0}：HTTP 500
assert 500 < 500
 +  where 500 = <Response [500]>.status_code

## 请求 / 响应

```json
{
  "request": {
    "query": {
      "page": 0,
      "size": 0
    },
    "body": null,
    "raw": null
  },
  "response": {
    "status": 500,
    "body": {
      "code": 50000,
      "msg": "系统异常",
      "traceId": "eb34ce2506294ea98c43595dabffef0e"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/FileController.java:49（endpoint）

```java
   44 |     private final OssProperties ossProperties;
   45 |     private final FileObjectMapper fileObjectMapper;
   46 | 
   47 |     @GetMapping("/view")
   48 |     @Operation(summary = "查看/下载文件", description = "从 OSS 代理输出文件流，供前端 img 展示（桶私有时不可直链）")
   49 |     public void view(
   50 |             @RequestParam("objectKey") String objectKey,
   51 |             HttpServletResponse response) throws java.io.IOException {
   52 |         if (!StringUtils.hasText(objectKey) || objectKey.contains("..") || objectKey.startsWith("/")) {
   53 |             throw new BizException(ErrorCode.BAD_REQUEST, "非法 objectKey");
   54 |         }
```
