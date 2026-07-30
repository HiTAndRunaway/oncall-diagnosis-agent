package org.example.exception;

/**
 * Thrown when a dependent service is unavailable.
 */
public class ServiceUnavailableException extends BizException {

    public ServiceUnavailableException(String serviceName, String detail) {
        super(
                serviceName.toUpperCase() + "_UNAVAILABLE",
                503,
                serviceName + " 服务不可用: " + detail
        );
    }
}
