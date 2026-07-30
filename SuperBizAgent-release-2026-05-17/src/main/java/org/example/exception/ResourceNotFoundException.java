package org.example.exception;

/**
 * 当请求的资源找不到时抛出。
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
