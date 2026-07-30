package org.example.exception;

/**
 * 当身份验证失败时抛出。
 */
public class AuthenticationException extends BizException {

    public AuthenticationException(String message) {
        super("AUTH_FAILED", 401, message);
    }
}
