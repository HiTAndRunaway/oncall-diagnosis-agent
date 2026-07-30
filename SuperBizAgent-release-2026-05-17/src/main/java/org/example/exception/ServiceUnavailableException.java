package org.example.exception;

/**
 * 当依赖的服务不可用时抛出。
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
