package org.example.exception;

/**
 * Thrown when an LLM service is unavailable or returns an error.
 */
public class LlmServiceException extends BizException {

    public LlmServiceException(String provider, String detail) {
        super(
                "LLM_UNAVAILABLE",
                502,
                provider + " LLM 服务异常: " + detail
        );
    }
}
