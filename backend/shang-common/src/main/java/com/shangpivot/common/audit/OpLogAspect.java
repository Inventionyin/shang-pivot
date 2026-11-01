package com.shangpivot.common.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link OpLog} 切面：方法成功记 SUCCESS、异常记 FAIL 后原样抛出。
 * <p>仅在存在 {@link OperationLogRecorder} Bean 时生效；跳过 Servlet / Spring 入参。
 */
@Slf4j
@Aspect
@Component
@ConditionalOnBean(OperationLogRecorder.class)
@RequiredArgsConstructor
public class OpLogAspect {

    private final OperationLogRecorder operationLogRecorder;
    private final ParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();

    /** 环绕标注方法：先采集入参快照，再按成败写审计。 */
    @Around("@annotation(opLog)")
    public Object around(ProceedingJoinPoint pjp, OpLog opLog) throws Throwable {
        Object params = opLog.saveParams() ? buildParams(pjp) : null;
        try {
            Object result = pjp.proceed();
            operationLogRecorder.record(opLog.module(), opLog.action(), params, "SUCCESS");
            return result;
        } catch (Throwable ex) {
            operationLogRecorder.record(opLog.module(), opLog.action(), params, "FAIL");
            throw ex;
        }
    }

    private Object buildParams(ProceedingJoinPoint pjp) {
        Object[] args = pjp.getArgs();
        if (args == null || args.length == 0) {
            return null;
        }
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        String[] names = nameDiscoverer.getParameterNames(method);
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg == null) {
                continue;
            }
            String className = arg.getClass().getName();
            if (className.startsWith("jakarta.servlet")
                    || className.startsWith("org.springframework")
                    || arg instanceof org.springframework.web.multipart.MultipartFile) {
                continue;
            }
            String name = (names != null && i < names.length && names[i] != null) ? names[i] : ("arg" + i);
            map.put(name, arg);
        }
        return map.isEmpty() ? null : map;
    }
}
