package com.shangpivot.common.exception;

import com.shangpivot.common.result.ErrorCode;
import lombok.Getter;

@Getter
public class BizException extends RuntimeException {
    private final int code;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMsg());
        this.code = errorCode.getCode();
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
