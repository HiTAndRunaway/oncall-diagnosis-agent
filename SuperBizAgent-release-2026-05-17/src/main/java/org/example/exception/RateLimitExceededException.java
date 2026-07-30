package org.example.exception;

/**
 * 当超过速率限制时抛出。
 */
public class RateLimitExceededException extends BizException {

    public RateLimitExceededException(String message) {
        super("RATE_LIMITED", 429, message);
    }
}
