# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[list_boundary\uff5cGET /api/files/view]-6f9cc6a9

- 运行：20261030-074606（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[list_boundary\uff5cGET /api/files/view] 边界-分页零值/负值/超大值｜GET /api/files/view
- 接口：GET /api/files/view
- 分类：边界值｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: list_boundary GET /api/files/view {'page': 0, 'size': 0}：HTTP 500
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
      "traceId": "777a57d388e04760b7521db428e0197a"
    }
  }
}
```

## 后端日志（traceId）

```
（无 500 堆栈可定位）
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/CustomerFileController.java:44（endpoint）

```java
   39 |     private final OssProperties ossProperties;
   40 |     private final FileObjectMapper fileObjectMapper;
   41 | 
   42 |     @GetMapping("/view")
   43 |     @Operation(summary = "查看文件")
   44 |     public void view(@RequestParam("objectKey") String objectKey, HttpServletResponse response) throws java.io.IOException {
   45 |         if (!StringUtils.hasText(objectKey) || objectKey.contains("..") || objectKey.startsWith("/")) {
   46 |             throw new BizException(ErrorCode.BAD_REQUEST, "非法 objectKey");
   47 |         }
   48 |         try {
   49 |             var meta = objectStorage.statObject(objectKey);
```
