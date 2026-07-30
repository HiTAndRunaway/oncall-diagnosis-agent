package org.example.exception;

/**
 * Thrown when a requested resource cannot be found.
 */
public class ResourceNotFoundException extends BizException {

    public ResourceNotFoundException(String resourceType, String identifier) {
        super(
                resourceType.toUpperCase() + "_NOT_FOUND",
                404,
                resourceType + " 不存在: " + identifier
        );
    }
}
