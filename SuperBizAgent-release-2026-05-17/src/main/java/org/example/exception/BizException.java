package org.example.exception;

/**
 * Abstract base exception for all business exceptions in the system.
 * All concrete business exceptions must extend this class.
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
