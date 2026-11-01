# 缺陷 API-VAL-006-d393eab7

- 运行：20261030-221123（git b13bf61）
- 用例：收货人姓名 65 字符（超列宽）应被拒
- 接口：/api/addresses
- 分类：边界值｜优先级：P0
- 断言意图：边界值-非法：预期 400（缺陷 D-06：实际 500）
- 失败摘要：业务码 50000（期望 40000）

## 请求 / 响应

```json
{
  "request": {
    "query": null,
    "body": {
      "name": "测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测测",
      "phone": "139****9242",
      "province": "北京市",
      "city": "北京市",
      "district": "朝阳区",
      "detail": "自动化测试地址 98313d",
      "isDefault": true
    },
    "raw": null
  },
  "response": {
    "status": 500,
    "body": {
      "code": 50000,
      "msg": "系统异常",
      "traceId": "b2a366b1248949dcb254d144e6118d28"
    }
  }
}
```

## 后端日志（traceId）

```
11:33:45.160 INFO  [b2a366b1248949dcb254d144e6118d28] - c.s.c.filter.AccessLogFilter → POST /api/addresses | ip=0:0:0:0:0:0:0:1 | ct=application/json
11:33:45.191 ERROR [b2a366b1248949dcb254d144e6118d28] 13948259242 c.s.c.e.GlobalExceptionHandler Unhandled error user=2116010641337688065/13948259242
org.springframework.dao.DataIntegrityViolationException:
### Error updating database.  Cause: org.postgresql.util.PSQLException: ERROR: value too long for type character varying(64)
### The error may exist in com/shangpivot/commerce/mapper/AddressMapper.java (best guess)
### The error may involve com.shangpivot.commerce.mapper.AddressMapper.insert-Inline
### The error occurred while setting parameters
### SQL: INSERT INTO commerce.address  ( id, customer_id, name, phone, province, city, district, detail, is_default, created_at, updated_at )  VALUES (  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?  )
### Cause: org.postgresql.util.PSQLException: ERROR: value too long for type character varying(64)
; ERROR: value too long for type character varying(64)
	at org.springframework.jdbc.support.SQLStateSQLExceptionTranslator.doTranslate(SQLStateSQLExceptionTranslator.java:140)
	at org.springframework.jdbc.support.AbstractFallbackSQLExceptionTranslator.translate(AbstractFallbackSQLExceptionTranslator.java:102)
	at org.springframework.jdbc.support.AbstractFallbackSQLExceptionTranslator.translate(AbstractFallbackSQLExceptionTranslator.java:111)
	at org.springframework.jdbc.support.AbstractFallbackSQLExceptionTranslator.translate(AbstractFallbackSQLExceptionTranslator.java:111)
	at org.mybatis.spring.MyBatisExceptionTranslator.translateExceptionIfPossible(MyBatisExceptionTranslator.java:95)
	at org.mybatis.spring.SqlSessionTemplate$SqlSessionInterceptor.invoke(SqlSessionTemplate.java:362)
	at jdk.proxy2/jdk.proxy2.$Proxy126.insert(Unknown Source)
	at org.mybatis.spring.SqlSessionTemplate.insert(SqlSessionTemplate.java:239)
	at com.baomidou.mybatisplus.core.override.MybatisMapperMethod.execute(MybatisMapperMethod.java:81)
	at com.baomidou.mybatisplus.core.override.MybatisMapperProxy$PlainMethodInvoker.invoke(MybatisMapperProxy.java:156)
	at com.baomidou.mybatisplus.core.override.MybatisMapperProxy.invoke(MybatisMapperProxy.java:93)
	at jdk.proxy2/jdk.proxy2.$Proxy155.insert(Unknown Source)
	at com.shangpivot.commerce.application.AddressService.create(AddressService.java:51)
	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at org.springframework.aop.support.AopUtils.invokeJoinpointUsingReflection(AopUtils.java:359)
	at org.springframework.aop.framework.ReflectiveMethodInvocation.invokeJoinpoint(ReflectiveMethodInvocation.java:190)
	at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:158)
	at org.springframework.transaction.interceptor.TransactionInterceptor$1.proceedWithInvocation(TransactionInterceptor.java:133)
	at org.springframework.transaction.interceptor.TransactionAspectSupport.invokeWithinTransaction(TransactionAspectSupport.java:371)
	at org.springframework.transaction.interceptor.TransactionInterceptor.invoke(TransactionInterceptor.java:130)
	at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:179)
	at org.springframework.aop.framework.CglibAopProxy$DynamicAdvisedInterceptor.intercept(CglibAopProxy.java:719)
	at com.shangpivot.commerce.application.AddressService$$SpringCGLIB$$0.create(<generated>)
	at com.shangpivot.commerce.controller.StorefrontController.createAddress(StorefrontController.java:133)
	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at org.springframework.web.method.support.InvocableHandlerMethod.doInvoke(InvocableHandlerMethod.java:252)
	at org.springframework.web.method.support.InvocableHandlerMethod.invokeForRequest(InvocableHandlerMethod.java:184)
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
	a
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/application/AddressService.java:51（None）

```java
   46 |             clearDefault(customerId);
   47 |             address.setIsDefault(true);
   48 |         } else {
   49 |             address.setIsDefault(false);
   50 |         }
   51 |         addressMapper.insert(address);
   52 |         return address.getId();
   53 |     }
   54 | 
   55 |     @Transactional
   56 |     public void update(Long id, AddressReq req) {
```
