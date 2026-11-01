# 缺陷 tests/api/test_endpoint_sweep.py::test_endpoint_sweep[empty_body\uff5cPOST /admin/products]-1d279f0b

- 运行：20261030-093954（git b13bf61）
- 用例：tests/api/test_endpoint_sweep.py::test_endpoint_sweep[empty_body\uff5cPOST /admin/products] 异常-空请求体｜POST /admin/products
- 接口：POST /admin/products
- 分类：异常｜优先级：P2
- 断言意图：对 135 个端点执行安全/逆向/边界/异常检查；单条用例标题由参数中的 check 与路径动态生成
- 失败摘要：AssertionError: empty_body POST /admin/products：HTTP 500
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
      "traceId": "e718acdbc2c941b99ffbae461c41563d"
    }
  }
}
```

## 后端日志（traceId）

```
2026-10-30 09:36:11.414 ERROR [http-nio-8086-exec-3] [trace=e718acdbc2c941b99ffbae461c41563d] [user=1/admin] c.s.c.exception.GlobalExceptionHandler - Unhandled error user=1/admin
org.springframework.dao.DataIntegrityViolationException:
### Error updating database.  Cause: org.postgresql.util.PSQLException: ERROR: null value in column "name" of relation "spu" violates not-null constraint
  详细：Failing row contains (2115981055900061697, null, null, null, null, null, null, null, DRAFT, 2026-10-30 09:36:11.4106+08, 2026-10-30 09:36:11.4106+08, f, null, 0, null).
### The error may exist in com/shangpivot/commerce/mapper/SpuMapper.java (best guess)
### The error may involve com.shangpivot.commerce.mapper.SpuMapper.insert-Inline
### The error occurred while setting parameters
### SQL: INSERT INTO commerce.spu  ( id,        status,   created_at, updated_at )  VALUES (  ?,        ?,   ?, ?  )
### Cause: org.postgresql.util.PSQLException: ERROR: null value in column "name" of relation "spu" violates not-null constraint
  详细：Failing row contains (2115981055900061697, null, null, null, null, null, null, null, DRAFT, 2026-10-30 09:36:11.4106+08, 2026-10-30 09:36:11.4106+08, f, null, 0, null).
; ERROR: null value in column "name" of relation "spu" violates not-null constraint
  详细：Failing row contains (2115981055900061697, null, null, null, null, null, null, null, DRAFT, 2026-10-30 09:36:11.4106+08, 2026-10-30 09:36:11.4106+08, f, null, 0, null).
	at org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator.doTranslate(SQLErrorCodeSQLExceptionTranslator.java:256)
	at org.springframework.jdbc.support.AbstractFallbackSQLExceptionTranslator.translate(AbstractFallbackSQLExceptionTranslator.java:102)
	at org.mybatis.spring.MyBatisExceptionTranslator.translateExceptionIfPossible(MyBatisExceptionTranslator.java:95)
	at org.mybatis.spring.SqlSessionTemplate$SqlSessionInterceptor.invoke(SqlSessionTemplate.java:362)
	at jdk.proxy2/jdk.proxy2.$Proxy125.insert(Unknown Source)
	at org.mybatis.spring.SqlSessionTemplate.insert(SqlSessionTemplate.java:239)
	at com.baomidou.mybatisplus.core.override.MybatisMapperMethod.execute(MybatisMapperMethod.java:81)
	at com.baomidou.mybatisplus.core.override.MybatisMapperProxy$PlainMethodInvoker.invoke(MybatisMapperProxy.java:156)
	at com.baomidou.mybatisplus.core.override.MybatisMapperProxy.invoke(MybatisMapperProxy.java:93)
	at jdk.proxy2/jdk.proxy2.$Proxy158.insert(Unknown Source)
	at com.shangpivot.commerce.application.ProductService.create(ProductService.java:186)
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
	at com.shangpivot.commerce.application.ProductService$$SpringCGLIB$$0.create(<generated>)
	at com.shangpivot.commerce.controller.ProductController.create(ProductController.java:48)
	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at org.springframework.aop.support.AopUtils.invokeJoinpointUsingReflection(AopUtils.java:359)
	at org.springframework.aop.framework.ReflectiveMethodInvocation.invokeJoinpoint(ReflectiveMethodInvocation.java:190)
	at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:158)
	at org.springframework.aop.aspectj.MethodInvocationProceedingJoinPoint.proceed(MethodInvocationProceedingJoinPoint.java:82)
	at com.shangpivot.common.audit.OpLogAspect.around(OpLogAspect.java:37)
	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at org.springframework.aop.aspectj.AbstractAspectJAdvice.invokeAdviceMethodWithGivenArgs(AbstractAspectJAdvice.java:648)
	at org.springframework.aop.aspectj.AbstractAspectJAdvice.invokeAdviceMethod(AbstractAspectJAdvice.java:630)
	at org.springframework.aop.aspectj.AspectJAroundAdvice.invoke(AspectJAroundAdvice.java:70)
	at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:168)
	at org.springframework.aop.interceptor.ExposeInvocationInterceptor.invoke(ExposeInvocationInterceptor.java:96)
	at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:179)
	at org.springframework.aop.framework.CglibAopProxy$DynamicAdvisedInterceptor.intercept(CglibAopProxy.java:719)
	at com.shangpivot.commerce.controller.ProductController$$SpringCGLIB$$0.create(<generated>)
	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at org.springframework.web.method.support.InvocableHandlerMethod.doInvoke(InvocableHandlerMethod.java:252)
	at org.springframework.web.method.support.InvocableHandlerMethod.invokeForRequest(InvocableHandlerMethod.java:184)
	at org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod.in
```

## 代码位置

- backend/shang-commerce/src/main/java/com/shangpivot/commerce/application/ProductService.java:186（None）

```java
  181 |             if (!req.getScheduleOnAt().isAfter(java.time.OffsetDateTime.now())) {
  182 |                 throw new BizException(ErrorCode.BAD_REQUEST, "定时上架时间须晚于当前");
  183 |             }
  184 |             spu.setScheduleOnAt(req.getScheduleOnAt());
  185 |         }
  186 |         spuMapper.insert(spu);
  187 |         saveSkus(spu.getId(), req.getSkus(), true);
  188 |         return spu.getId();
  189 |     }
  190 | 
  191 |     /** 更新 SPU；若带 skus 则删旧建新（不强制重建库存）。 */
```
