package com.shangpivot.common.result;

import com.shangpivot.common.util.TraceIdUtil;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "统一响应体")
public class Result<T> {
    @Schema(description = "业务码，0 表示成功", example = "0")
    private int code;
    @Schema(description = "提示信息", example = "ok")
    private String msg;
    @Schema(description = "业务数据")
    private T data;
    @Schema(description = "链路追踪 ID", example = "d8d822c1006a4eebbf66e62ebacdbcfb")
    private String traceId;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = ErrorCode.SUCCESS.getCode();
        r.msg = ErrorCode.SUCCESS.getMsg();
        r.data = data;
        r.traceId = TraceIdUtil.get();
        return r;
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return fail(errorCode.getCode(), errorCode.getMsg());
    }

    public static <T> Result<T> fail(int code, String msg) {
        Result<T> r = new Result<>();
        r.code = code;
        r.msg = msg;
        r.traceId = TraceIdUtil.get();
        return r;
    }
}
