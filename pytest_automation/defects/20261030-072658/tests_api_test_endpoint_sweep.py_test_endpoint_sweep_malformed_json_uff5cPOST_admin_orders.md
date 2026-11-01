# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/orders/{id}/ship]-b8ce03f0

- 运行：20261030-072658（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/orders/{id}/ship] 异常-畸形 JSON 请求体｜POST /admin/orders/{id}/ship
- 接口：POST /admin/orders/{id}/ship
- 分类：异常｜优先级：P2
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: malformed_json POST /admin/orders/{id}/ship：HTTP 500
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
      "traceId": "77917c64a86848bba70a69762acda320"
    }
  }
}
```

## 后端日志（traceId）

```
2026-10-30 07:19:16.007 ERROR [http-nio-8086-exec-1] [trace=77917c64a86848bba70a69762acda320] [user=1/admin] c.s.c.exception.GlobalExceptionHandler - Unhandled error user=1/admin
org.springframework.http.converter.HttpMessageNotReadableException: JSON parse error: Unexpected end-of-input within/between Object entries
	at org.springframework.http.converter.AbstractJacksonHttpMessageConverter.readJavaType(AbstractJacksonHttpMessageConverter.java:363)
	at org.springframework.http.converter.AbstractJacksonHttpMessageConverter.read(AbstractJacksonHttpMessageConverter.java:319)
	at org.springframework.web.servlet.mvc.method.annotation.AbstractMessageConverterMethodArgumentResolver.readWithMessageConverters(AbstractMessageConverterMethodArgumentResolver.java:203)
	at org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor.readWithMessageConverters(RequestResponseBodyMethodProcessor.java:181)
	at org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor.resolveArgument(RequestResponseBodyMethodProcessor.java:156)
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
	at org.springframework.web.servlet.FrameworkServlet.doPost(FrameworkServlet.java:903)
	at jakarta.servlet.http.HttpServlet.service(HttpServlet.java:649)
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
	at org.springframework.security.web.authenti
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/controller/OrderController.java:115（endpoint）

```java
  110 |     }
  111 | 
  112 |     @PostMapping("/{id}/ship")
  113 |     @OpLog(module = "order", action = "ship")
  114 |     @Operation(summary = "订单发货", description = "对待发货订单录入物流信息并扣减库存")
  115 |     public Result<Void> ship(
  116 |             @Parameter(description = "订单ID", example = "1") @PathVariable Long id,
  117 |             @RequestBody OrderService.ShipReq req) {
  118 |         orderService.ship(id, req);
  119 |         return Result.ok();
  120 |     }
```
