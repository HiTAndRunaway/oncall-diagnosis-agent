package org.example.exception;

/**
 * 当用户输入无效或格式错误时抛出。
 */
public class InvalidInputException extends BizException {

    public InvalidInputException(String message) {
        super("INVALID_INPUT", 400, message);
    }
}
