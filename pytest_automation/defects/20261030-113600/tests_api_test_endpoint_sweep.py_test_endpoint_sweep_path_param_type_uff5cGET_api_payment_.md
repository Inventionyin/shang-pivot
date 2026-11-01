# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[path_param_type\uff5cGET /api/payment/{orderId}/status]-f34ad70f

- 运行：20261030-113600（git b13bf61）
- 用例：逆向-路径参数类型错误
- 接口：/api/payment/{orderId}/status
- 分类：功能逆向｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：路径参数类型错误 · HTTP 500 系统异常

## 请求 / 响应

```json
{
  "request": {
    "query": {
      "page": 1,
      "size": 1
    },
    "body": null,
    "raw": null
  },
  "response": {
    "status": 500,
    "body": {
      "code": 50000,
      "msg": "系统异常",
      "traceId": "af0ab06c85c442a1b54c30f939ea5737"
    }
  }
}
```

## 后端日志（traceId）

```
11:33:22.355 INFO  [af0ab06c85c442a1b54c30f939ea5737] - c.s.c.filter.AccessLogFilter → GET /api/payment/abc/status?page=1&size=1 | ip=0:0:0:0:0:0:0:1 | ct=application/json
11:33:22.358 ERROR [af0ab06c85c442a1b54c30f939ea5737] 13950449062 c.s.c.e.GlobalExceptionHandler Unhandled error user=2116009473437933570/13950449062
org.springframework.web.method.annotation.MethodArgumentTypeMismatchException: Method parameter 'orderId': Failed to convert value of type 'java.lang.String' to required type 'java.lang.Long'; For input string: "abc"
	at org.springframework.web.method.annotation.AbstractNamedValueMethodArgumentResolver.convertIfNecessary(AbstractNamedValueMethodArgumentResolver.java:295)
	at org.springframework.web.method.annotation.AbstractNamedValueMethodArgumentResolver.resolveArgument(AbstractNamedValueMethodArgumentResolver.java:134)
	at org.springframework.web.method.support.HandlerMethodArgumentResolverComposite.resolveArgument(HandlerMethodArgumentResolverComposite.java:122)
	at org.springframework.web.method.support.InvocableHandlerMethod.getMethodArgumentValues(InvocableHandlerMethod.java:224)
	at org.springframework.web.method.support.InvocableHandlerMethod.invokeForRequest(InvocableHandlerMethod.java:174)
	at org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod.invokeAndHandle(ServletInvocableHandlerMethod.java:117)
	at org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter.invokeHandlerMethod(RequestMappingHandlerAdapter.java:934)
	at org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter.handleInternal(RequestMappingHandlerAdapter.java:853)
	at org.springframework.web.servlet.mvc.method.AbstractHandlerMethodAdapter.handle(AbstractHandlerMethodAdapter.java:86)
	at org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:963)
	at org.springframework.web.servlet.DispatcherServlet.doService(DispatcherServlet.java:866)
	at org.springframework.web.servlet.FrameworkServlet.processRequest(FrameworkServlet.java:1000)
	at org.springframework.web.servlet.FrameworkServlet.doGet(FrameworkServlet.java:892)
	at jakarta.servlet.http.HttpServlet.service(HttpServlet.java:622)
	at org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:874)
	at jakarta.servlet.http.HttpServlet.service(HttpServlet.java:710)
	at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:132)
	at org.apache.tomcat.websocket.server.WsFilter.doFilter(WsFilter.java:59)
	at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:111)
	at org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:110)
	at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:111)
	at com.github.xiaoymin.knife4j.extend.filter.basic.JakartaServletSecurityBasicAuthFilter.doFilter(JakartaServletSecurityBasicAuthFilter.java:68)
	at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:111)
	at org.springframework.web.filter.CompositeFilter$VirtualFilterChain.doFilter(CompositeFilter.java:108)
	at org.springframework.security.web.FilterChainProxy.lambda$doFilterInternal$2(FilterChainProxy.java:235)
	at org.springframework.security.web.ObservationFilterChainDecorator$FilterObservation$SimpleFilterObservation.lambda$wrap$1(ObservationFilterChainDecorator.java:491)
	at org.springframework.security.web.ObservationFilterChainDecorator$AroundFilterObservation$SimpleAroundFilterObservation.lambda$wrap$1(ObservationFilterChainDecorator.java:353)
	at org.springframework.security.web.ObservationFilterChainDecorator.lambda$wrapSecured$0(ObservationFilterChainDecorator.java:84)
	at org.springframework.security.web.ObservationFilterChainDecorator$VirtualFilterChain.doFilter(ObservationFilterChainDecorator.java:131)
	at org.springframework.security.web.access.intercept.AuthorizationFilter.doFilter(AuthorizationFilter.java:101)
	at org.springframework.security.web.ObservationFilterChainDecorator$ObservationFilter.wrapFilter(ObservationFilterChainDecorator.java:243)
	at org.springframework.security.web.ObservationFilterChainDecorator$ObservationFilter.doFilter(ObservationFilterChainDecorator.java:230)
	at org.springframework.security.web.ObservationFilterChainDecorator$VirtualFilterChain.doFilter(ObservationFilterChainDecorator.java:140)
	at org.springframework.security.web.access.ExceptionTranslationFilter.doFilter(ExceptionTranslationFilter.java:126)
	at org.springframework.security.web.access.ExceptionTranslationFilter.doFilter(ExceptionTranslationFilter.java:120)
	at org.springframework.security.web.ObservationFilterChainDecorator$ObservationFilter.wrapFilter(ObservationFilterChainDecorator.java:243)
	at org.springframework.security.web.ObservationFilterChainDecorator$ObservationFilter.doFilter(ObservationFilterChainDecorator.java:230)
	at org.springframework.security.web.ObservationFilterChainDecorator$VirtualFilterChain.doFilter(ObservationFilterChainDecorator.java:140)
	at org.springframework.security.web.session.SessionManagementFilter.doFilter(SessionManagementFilter.java:132)
	at org.springframework.security.web.session.SessionManagementFilter.doFilter(SessionManagementFilter.java:86)
	at org.springframework.security.web.ObservationFilterChainDecorator$ObservationFilter.wrapFilter(ObservationFilterChainDecorator.java:243)
	at org.springframework.security.web.ObservationFilterChainDecorator$ObservationFilter.doFilter(ObservationFilterChainDecorator.java:230)
	at org.springframework.security.web.ObservationFilterChainDecorator$VirtualFilterChain.doFilter(ObservationFilterChainDecorator.java:140)
	at org.springframework.security.web.authentication.AnonymousAuthenticationFilter.doFilter(AnonymousAuthenticationFilter.java:100)
	at org.springframework.security.web.ObservationFilterChainDecorator$ObservationFilter.wrapFilter(ObservationFilterChainDecorator.java:243)
	at org.sp
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/StorefrontController.java:248（endpoint）

```java
  243 |         Long orderId = Long.valueOf(body.get("orderId").toString());
  244 |         return Result.ok(paymentSandboxService.pay(orderId));
  245 |     }
  246 | 
  247 |     @GetMapping("/api/payment/{orderId}/status")
  248 |     public Result<Map<String, Object>> payStatus(@PathVariable Long orderId) {
  249 |         return Result.ok(paymentSandboxService.status(orderId));
  250 |     }
  251 | 
  252 |     @PostMapping("/api/after-sales")
  253 |     public Result<Long> applyAfterSale(@RequestBody AfterSaleService.ApplyReq req) {
```
