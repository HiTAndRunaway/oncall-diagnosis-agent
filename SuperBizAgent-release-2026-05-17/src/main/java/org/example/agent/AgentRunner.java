package org.example.agent;

import org.example.dto.AgentEvent;
import org.example.dto.AiOpsResult;
import reactor.core.publisher.Flux;

/**
 * 智能体执行运行器接口。
 * 提供同步、流式和多智能体编排三种执行模式。
 */
public interface AgentRunner {

    /**
     * 同步执行智能体并返回完整响应。
     *
     * @param systemPrompt 智能体的系统提示词
     * @param userMessage  用户消息 / 任务描述
     * @return 完整的智能体响应文本
     */
    String execute(String systemPrompt, String userMessage);

    /**
     * 以流式模式执行智能体，返回 AgentEvent 的 Flux 流。
     *
     * @param systemPrompt 智能体的系统提示词
     * @param userMessage  用户消息 / 任务描述
     * @return 智能体事件的反应式流
     */
    Flux<AgentEvent> executeStream(String systemPrompt, String userMessage);

    /**
     * 执行多智能体 AIOps 编排并返回结果。
     *
     * @param taskPrompt AIOps 分析的任务提示词
     * @return AIOps 分析结果
     */
    AiOpsResult executeOrchestration(String taskPrompt);
}
