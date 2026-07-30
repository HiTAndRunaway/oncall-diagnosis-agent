package org.example.exception;

/**
 * Thrown when user input is invalid or malformed.
 */
public class InvalidInputException extends BizException {

    public InvalidInputException(String message) {
        super("INVALID_INPUT", 400, message);
    }
}
