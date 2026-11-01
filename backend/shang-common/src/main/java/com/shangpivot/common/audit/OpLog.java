package com.shangpivot.common.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要写入操作审计日志的接口方法（跨权限 / 商品 / 系统等模块共用）。
 * <p>由 {@link OpLogAspect} 环绕织入，经 {@link OperationLogRecorder} 异步落库。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OpLog {
    /** 业务模块，如 auth / product / order / config */
    String module();

    /** 动作，如 create / update / delete / ship */
    String action();

    /** 是否记录方法入参（会脱敏） */
    boolean saveParams() default true;
}
