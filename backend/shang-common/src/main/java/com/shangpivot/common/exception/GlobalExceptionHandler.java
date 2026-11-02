package com.shangpivot.common.exception;

import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.result.Result;
import com.shangpivot.common.util.TraceIdUtil;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    /** 业务失败计数器（观测平台指标名 business_failures_total，按错误码分标签） */
    private final MeterRegistry meterRegistry;

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        // 业务失败是 HTTP 200 + code≠0 的静默失败，既不产生 5xx 也不会进错误率统计，
        // 只有计数器能让告警与图表看见它（规则见 observability/prometheus/rules/）
        meterRegistry.counter("business_failures", "code", String.valueOf(e.getCode())).increment();
        log.warn("BizException code={} msg={} user={}/{}",
                e.getCode(), e.getMessage(), TraceIdUtil.userId(), TraceIdUtil.username());
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValid(Exception e) {
        String msg = ErrorCode.BAD_REQUEST.getMsg();
        if (e instanceof MethodArgumentNotValidException manv && manv.getBindingResult().getFieldError() != null) {
            msg = manv.getBindingResult().getFieldError().getDefaultMessage();
        } else if (e instanceof BindException be && be.getBindingResult().getFieldError() != null) {
            msg = be.getBindingResult().getFieldError().getDefaultMessage();
        }
        log.warn("Validation failed msg={} user={}/{}", msg, TraceIdUtil.userId(), TraceIdUtil.username());
        return Result.fail(ErrorCode.BAD_REQUEST.getCode(), msg);
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleDenied(AccessDeniedException e) {
        log.warn("Access denied: {} user={}/{}", e.getMessage(), TraceIdUtil.userId(), TraceIdUtil.username());
        return Result.fail(ErrorCode.FORBIDDEN);
    }

    /** 请求体不是合法 JSON / 字段类型无法反序列化：属于客户端错误，不应记 5xx。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("Malformed request body user={}/{}: {}",
                TraceIdUtil.userId(), TraceIdUtil.username(), e.getMostSpecificCause().getMessage());
        return Result.fail(ErrorCode.BAD_REQUEST.getCode(), "请求体格式错误");
    }

    /** 路径/查询参数类型不匹配（如 /api/products/abc）：400 而非 500。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("Parameter type mismatch: {}={} user={}/{}",
                e.getName(), e.getValue(), TraceIdUtil.userId(), TraceIdUtil.username());
        return Result.fail(ErrorCode.BAD_REQUEST.getCode(), "参数类型不正确: " + e.getName());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("Missing request parameter: {} user={}/{}",
                e.getParameterName(), TraceIdUtil.userId(), TraceIdUtil.username());
        return Result.fail(ErrorCode.BAD_REQUEST.getCode(), "缺少参数: " + e.getParameterName());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("Method not supported: {} user={}/{}",
                e.getMethod(), TraceIdUtil.userId(), TraceIdUtil.username());
        return Result.fail(ErrorCode.BAD_REQUEST.getCode(), "不支持的请求方法: " + e.getMethod());
    }

    /** 浏览器自动请求 favicon 等静态资源缺失，不应打 ERROR */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResource(NoResourceFoundException e) {
        log.debug("No resource: {}", e.getResourcePath());
        return Result.fail(ErrorCode.NOT_FOUND);
    }

    /** 远程库空闲连接被掐、网络抖动：连接池会换新连接，提示重试即可 */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result<Void> handleDbResource(DataAccessResourceFailureException e) {
        log.warn("DB connection broken (retry usually works) user={}/{}: {}",
                TraceIdUtil.userId(), TraceIdUtil.username(), e.getMostSpecificCause().getMessage());
        return Result.fail(ErrorCode.SYSTEM_ERROR.getCode(), "数据库连接中断，请稍后重试");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleOther(Exception e) {
        log.error("Unhandled error user={}/{}", TraceIdUtil.userId(), TraceIdUtil.username(), e);
        return Result.fail(ErrorCode.SYSTEM_ERROR);
    }
}
