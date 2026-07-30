package org.example.exception;

/**
 * Thrown when a rate limit has been exceeded.
 */
public class RateLimitExceededException extends BizException {

    public RateLimitExceededException(String message) {
        super("RATE_LIMITED", 429, message);
    }
}
