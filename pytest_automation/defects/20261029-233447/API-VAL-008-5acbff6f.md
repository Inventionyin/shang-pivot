# 缺陷 API-VAL-008-5acbff6f

- 运行：20261029-233447（git b13bf61）
- 用例：API-VAL-008 API-VAL-008 详细地址 256 字符（超列宽）应被拒
- 接口：POST /api/addresses
- 分类：边界值｜优先级：P0
- 断言意图：边界值-非法：预期 400（缺陷 D-07：实际 500）
- 失败摘要：AssertionError: 业务码期望 40000 实际 50000；msg=系统异常

## 请求 / 响应

```json
{
  "request": {
    "query": null,
    "body": {
      "name": "自动化测试",
      "phone": "139****5807",
      "province": "北京市",
      "city": "北京市",
      "district": "朝阳区",
      "detail": "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx...",
      "isDefault": true
    },
    "raw": null
  },
  "response": {
    "status": 500,
    "body": {
      "code": 50000,
      "msg": "系统异常",
      "traceId": "808623eed03d4700b0fd7c7ef246912e"
    }
  }
}
```

## 后端日志（traceId）

```
2026-10-29 14:51:28.285 ERROR [http-nio-8086-exec-9] [trace=808623eed03d4700b0fd7c7ef246912e] [user=2115698011096076290/13960815807] c.s.c.exception.GlobalExceptionHandler - Unhandled error user=2115698011096076290/13960815807
org.springframework.dao.DataIntegrityViolationException:
### Error updating database.  Cause: org.postgresql.util.PSQLException: ERROR: value too long for type character varying(255)
### The error may exist in com/shangpivot/commerce/mapper/AddressMapper.java (best guess)
### The error may involve com.shangpivot.commerce.mapper.AddressMapper.insert-Inline
### The error occurred while setting parameters
### SQL: INSERT INTO commerce.address  ( id, customer_id, name, phone, province, city, district, detail, is_default, created_at, updated_at )  VALUES (  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?  )
### Cause: org.postgresql.util.PSQLException: ERROR: value too long for type character varying(255)
; ERROR: value too long for type character varying(255)
	at org.springframework.jdbc.support.SQLStateSQLExceptionTranslator.doTranslate(SQLStateSQLExceptionTranslator.java:140)
	at org.springframework.jdbc.support.AbstractFallbackSQLExceptionTranslator.translate(AbstractFallbackSQLExceptionTranslator.java:102)
	at org.springframework.jdbc.support.AbstractFallbackSQLExceptionTranslator.translate(AbstractFallbackSQLExceptionTranslator.java:111)
	at org.springframework.jdbc.support.AbstractFallbackSQLExceptionTranslator.translate(AbstractFallbackSQLExceptionTranslator.java:111)
	at org.mybatis.spring.MyBatisExceptionTranslator.translateExceptionIfPossible(MyBatisExceptionTranslator.java:95)
	at org.mybatis.spring.SqlSessionTemplate$SqlSessionInterceptor.invoke(SqlSessionTemplate.java:362)
	at jdk.proxy2/jdk.proxy2.$Proxy121.insert(Unknown Source)
	at org.mybatis.spring.SqlSessionTemplate.insert(SqlSessionTemplate.java:239)
	at com.baomidou.mybatisplus.core.override.MybatisMapperMethod.execute(MybatisMapperMethod.java:81)
	at com.baomidou.mybatisplus.core.override.MybatisMapperProxy$PlainMethodInvoker.invoke(MybatisMapperProxy.java:156)
	at com.baomidou.mybatisplus.core.override.MybatisMapperProxy.invoke(MybatisMapperProxy.java:93)
	at jdk.proxy2/jdk.proxy2.$Proxy150.insert(Unknown Source)
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
	at org.spri
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/application/AddressService.java:51

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
