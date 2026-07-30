package org.example.exception;

/**
 * 系统中所有业务异常的抽象基类。
 * 所有具体的业务异常必须继承此类。
 */
public abstract class BizException extends RuntimeException {

    private final String errorCode;
    private final int httpStatus;

    protected BizException(String errorCode, int httpStatus, String message) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    protected BizException(String errorCode, int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
