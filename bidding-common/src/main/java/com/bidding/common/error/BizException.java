package com.bidding.common.error;

/**
 * 可预期的业务异常（参数错、无权限、资源不存在等）。
 * 由全局异常处理器转成 ApiResponse，不要用来包装未知系统故障。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
