# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[list_boundary\uff5cGET /admin/dict-items/by-code]-d474c469

- 运行：20261030-105936（git b13bf61）
- 用例：边界-分页零值/负值/超大值
- 接口：/admin/dict-items/by-code
- 分类：边界值｜优先级：
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：分页零值/负值/超大值 · HTTP 500 系统异常

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
      "traceId": "4a30a43b1ece40c99c97df0a2a21d6d4"
    }
  }
}
```

## 后端日志（traceId）

```
2026-10-30 10:55:35.595 ERROR [http-nio-8086-exec-5] [trace=4a30a43b1ece40c99c97df0a2a21d6d4] [user=1/admin] c.s.c.exception.GlobalExceptionHandler - Unhandled error user=1/admin
org.springframework.web.bind.MissingServletRequestParameterException: Required request parameter 'code' for method parameter type String is not present
	at org.springframework.web.method.annotation.RequestParamMethodArgumentResolver.handleMissingValueInternal(RequestParamMethodArgumentResolver.java:219)
	at org.springframework.web.method.annotation.RequestParamMethodArgumentResolver.handleMissingValue(RequestParamMethodArgumentResolver.java:195)
	at org.springframework.web.method.annotation.AbstractNamedValueMethodArgumentResolver.resolveArgument(AbstractNamedValueMethodArgumentResolver.java:123)
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
	at org.springframework.security.web.ObservationFilterChainDecorator$Observat
```

## 代码位置

- backend/shang-identity/src/main/java/com/shangpivot/identity/controller/DictController.java:100（endpoint）

```java
   95 |                 .orderByAsc(DictItem::getSort)));
   96 |     }
   97 | 
   98 |     @GetMapping("/admin/dict-items/by-code")
   99 |     @Operation(summary = "按编码查询字典项", description = "根据字典编码查询字典项列表")
  100 |     public Result<List<DictItem>> itemsByCode(
  101 |             @Parameter(description = "字典编码", example = "user_status", required = true) @RequestParam String code) {
  102 |         Dict dict = dictMapper.selectOne(new LambdaQueryWrapper<Dict>().eq(Dict::getCode, code));
  103 |         if (dict == null) {
  104 |             return Result.ok(List.of());
  105 |         }
```
