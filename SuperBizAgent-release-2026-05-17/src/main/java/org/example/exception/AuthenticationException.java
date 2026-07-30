package org.example.exception;

/**
 * Thrown when authentication fails.
 */
public class AuthenticationException extends BizException {

    public AuthenticationException(String message) {
        super("AUTH_FAILED", 401, message);
    }
}
