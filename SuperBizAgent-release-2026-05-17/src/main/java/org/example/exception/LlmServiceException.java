package org.example.exception;

/**
 * 当LLM服务不可用或返回错误时抛出。
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
