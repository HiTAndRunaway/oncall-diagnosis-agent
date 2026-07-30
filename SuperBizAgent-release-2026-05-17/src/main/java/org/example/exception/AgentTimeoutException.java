package org.example.exception;

/**
 * Thrown when an agent execution exceeds its timeout.
 */
public class AgentTimeoutException extends BizException {

    public AgentTimeoutException(int timeoutSeconds) {
        super(
                "AGENT_TIMEOUT",
                504,
                "分析超时 (" + timeoutSeconds + " 秒)，已生成基于知识推断的兜底报告"
        );
    }
}
