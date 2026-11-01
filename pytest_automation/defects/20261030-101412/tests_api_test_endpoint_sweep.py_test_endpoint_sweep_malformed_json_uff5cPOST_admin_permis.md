# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[malformed_json\uff5cPOST /admin/permissions]-ee6f4c53

- 运行：20261030-101412（git b13bf61）
- 用例：异常-畸形 JSON 请求体
- 接口：/admin/permissions
- 分类：异常｜优先级：P2
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：畸形 JSON 请求体 · HTTP 500 系统异常

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
      "traceId": "199ced0d9646419db880f49dee237330"
    }
  }
}
```

## 后端日志（traceId）

```
2026-10-30 09:36:01.879 ERROR [http-nio-8086-exec-2] [trace=199ced0d9646419db880f49dee237330] [user=1/admin] c.s.c.exception.GlobalExceptionHandler - Unhandled error user=1/admin
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
