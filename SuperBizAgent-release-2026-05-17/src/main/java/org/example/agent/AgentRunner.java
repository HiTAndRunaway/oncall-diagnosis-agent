package org.example.agent;

import org.example.dto.AgentEvent;
import org.example.dto.AiOpsResult;
import reactor.core.publisher.Flux;

/**
 * Interface for agent execution runners.
 * Provides synchronous, streaming, and orchestration execution modes.
 */
public interface AgentRunner {

    /**
     * Execute an agent synchronously and return the complete response.
     *
     * @param systemPrompt the system prompt for the agent
     * @param userMessage  the user message / task description
     * @return the complete agent response text
     */
    String execute(String systemPrompt, String userMessage);

    /**
     * Execute an agent in streaming mode, returning a Flux of agent events.
     *
     * @param systemPrompt the system prompt for the agent
     * @param userMessage  the user message / task description
     * @return a reactive stream of agent events
     */
    Flux<AgentEvent> executeStream(String systemPrompt, String userMessage);

    /**
     * Execute a multi-agent AIOps orchestration and return the result.
     *
     * @param taskPrompt the task prompt for the AIOps analysis
     * @return the AIOps analysis result
     */
    AiOpsResult executeOrchestration(String taskPrompt);
}
