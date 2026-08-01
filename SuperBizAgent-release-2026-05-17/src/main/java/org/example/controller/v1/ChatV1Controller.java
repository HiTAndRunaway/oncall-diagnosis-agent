package org.example.controller.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.agent.AgentRunner;
import org.example.agent.router.IntentCategory;
import org.example.agent.router.IntentResult;
import org.example.agent.router.IntentRouter;
import org.example.agent.tool.RecallMemoryTool;
import org.example.dto.AgentEvent;
import org.example.dto.AiOpsResult;
import org.example.dto.ApiResponse;
import org.example.dto.ChatRequest;
import org.example.dto.ChatResponse;
import org.example.dto.ClearRequest;
import org.example.dto.SessionInfoResponse;
import org.example.exception.InvalidInputException;
import org.example.exception.ResourceNotFoundException;
import org.example.service.AiOpsService;
import org.example.service.ChatService;
import org.example.service.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 聊天对话 V1 控制器
 * 提供对话交互与流式响应接口
 */
@Tag(name = "聊天对话", description = "对话交互与流式响应接口")
@RestController
@RequestMapping("/api/v1")
public class ChatV1Controller {

    private static final Logger logger = LoggerFactory.getLogger(ChatV1Controller.class);

    @Autowired
    private AiOpsService aiOpsService;

    @Autowired
    private ChatService chatService;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private AgentRunner agentRunner;

    @Autowired
    private IntentRouter intentRouter;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 普通对话接口（支持工具调用）
     */
    @Operation(summary = "普通对话", description = "发送消息并获取 AI 回复，支持自动工具调用")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "对话成功",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "参数校验失败"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "请求频率超限"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "LLM 服务暂不可用")
    })
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
        logger.info("收到对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

        // 参数校验
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            throw new InvalidInputException("问题内容不能为空");
        }

        // 意图识别路由
        IntentResult intent = intentRouter.classify(request.getQuestion());
        logger.info("[IntentRouter] category={} confidence={}", intent.getCategory(), intent.getConfidence());

        if (intent.getCategory() == IntentCategory.ALERT_DIAGNOSIS) {
            return handleAIOpsRoute(request);
        }

        // 从 SecurityContext 获取当前用户 ID
        String userId = getCurrentUserId();
        RecallMemoryTool.setCurrentUserId(userId);
        try {
            // 获取或创建会话
            SessionManager.SessionContext ctx = sessionManager.getOrCreateSession(request.getId());
            String sessionId = ctx.getSessionId();

            // 决定使用摘要还是详情
            List<Map<String, String>> history;
            if (ctx.hasSummary()) {
                history = Collections.emptyList();
            } else {
                history = ctx.getHistory();
            }
            logger.info("会话历史消息对数: {}, 摘要模式: {}", history.size() / 2, ctx.hasSummary());

            // 构建系统提示词
            String systemPrompt = chatService.buildSystemPrompt(history, ctx.getSummary(), userId);

            // 意图特定的提示词调整
            if (intent.getCategory() == IntentCategory.UNCLEAR) {
                systemPrompt += "\n\n如果用户意图不明确，请先友好地引导用户澄清：是遇到了系统告警需要排查，还是想了解相关知识？\n";
            } else if (intent.getCategory() == IntentCategory.KNOWLEDGE_RETRIEVAL) {
                systemPrompt += "\n\n用户正在查询内部知识文档，请优先使用 queryInternalDocs 工具检索相关文档后回答。\n";
            }

            // 通过 AgentRunner 执行对话
            String fullAnswer = agentRunner.execute(systemPrompt, request.getQuestion());

            // 更新会话历史
            sessionManager.addMessage(sessionId, request.getQuestion(), fullAnswer, userId);
            logger.info("已更新会话历史 - SessionId: {}", sessionId);

            return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(fullAnswer, sessionId)));
        } finally {
            RecallMemoryTool.clearCurrentUserId();
        }
    }

    /**
     * Agent 流式对话接口（SSE 模式）
     */
    @Operation(summary = "流式对话", description = "SSE 流式输出 AI 回复，支持实时工具调用状态反馈")
    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时

        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            logger.warn("问题内容为空");
            try {
                emitter.send(SseEmitter.event().name("message")
                        .data(AgentEvent.error("问题内容不能为空"), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        // 意图识别路由
        IntentResult intent = intentRouter.classify(request.getQuestion());
        logger.info("[IntentRouter] category={} confidence={}", intent.getCategory(), intent.getConfidence());

        if (intent.getCategory() == IntentCategory.ALERT_DIAGNOSIS) {
            handleAIOpsRouteStream(emitter);
            return emitter;
        }

        String userId = getCurrentUserId();
        RecallMemoryTool.setCurrentUserId(userId);
        try {
            logger.info("收到 Agent 流式对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

            SessionManager.SessionContext ctx = sessionManager.getOrCreateSession(request.getId());
            String sessionId = ctx.getSessionId();

            List<Map<String, String>> history;
            if (ctx.hasSummary()) {
                history = Collections.emptyList();
            } else {
                history = ctx.getHistory();
            }
            logger.info("Agent 会话历史消息对数: {}, 摘要模式: {}", history.size() / 2, ctx.hasSummary());

            String systemPrompt = chatService.buildSystemPrompt(history, ctx.getSummary(), userId);

            StringBuilder fullAnswerBuilder = new StringBuilder();

            Flux<AgentEvent> stream = agentRunner.executeStream(systemPrompt, request.getQuestion());

            stream.subscribe(
                    event -> {
                        try {
                            switch (event.getType()) {
                                case CONTENT_CHUNK -> {
                                    String chunk = event.getData();
                                    if (chunk != null && !chunk.isEmpty()) {
                                        fullAnswerBuilder.append(chunk);
                                        emitter.send(SseEmitter.event()
                                                .name("message")
                                                .data(AgentEvent.content(chunk), MediaType.APPLICATION_JSON));
                                        logger.debug("发送流式内容: {}", chunk);
                                    }
                                }
                                case TOOL_CALL_END -> {
                                    logger.info("工具调用完成: {}", event.getData());
                                }
                                case TOOL_CALL_START -> {
                                    logger.debug("工具调用开始: {}", event.getData());
                                }
                                case ERROR -> {
                                    logger.error("Agent 流式执行出错: {}", event.getData());
                                }
                                case DONE -> {
                                    // 完成处理在 onComplete 回调中处理
                                }
                            }
                        } catch (IOException e) {
                            logger.error("发送流式消息失败", e);
                            throw new RuntimeException(e);
                        }
                    },
                    error -> {
                        RecallMemoryTool.clearCurrentUserId();
                        logger.error("Agent 流式对话失败", error);
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(AgentEvent.error(error.getMessage()), MediaType.APPLICATION_JSON));
                        } catch (IOException ex) {
                            logger.error("发送错误消息失败", ex);
                        }
                        emitter.completeWithError(error);
                    },
                    () -> {
                        try {
                            RecallMemoryTool.clearCurrentUserId();
                            String fullAnswer = fullAnswerBuilder.toString();
                            logger.info("Agent 流式对话完成 - SessionId: {}, 答案长度: {}",
                                    sessionId, fullAnswer.length());

                            sessionManager.addMessage(sessionId, request.getQuestion(), fullAnswer, userId);
                            logger.info("已更新会话历史 - SessionId: {}", sessionId);

                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(AgentEvent.done(sessionId), MediaType.APPLICATION_JSON));
                            emitter.complete();
                        } catch (IOException e) {
                            logger.error("发送完成消息失败", e);
                            emitter.completeWithError(e);
                        }
                    }
            );
        } catch (Exception e) {
            RecallMemoryTool.clearCurrentUserId();
            logger.error("Agent 对话初始化失败", e);
            try {
                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(AgentEvent.error(e.getMessage()), MediaType.APPLICATION_JSON));
            } catch (IOException ex) {
                logger.error("发送错误消息失败", ex);
            }
            emitter.completeWithError(e);
        }

        return emitter;
    }

    /**
     * 清空会话历史
     */
    @Operation(summary = "清空会话", description = "清除指定会话的全部对话历史")
    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ClearRequest request) {
        logger.info("收到清空会话历史请求 - SessionId: {}", request.getId());

        if (request.getId() == null || request.getId().isEmpty()) {
            throw new InvalidInputException("会话ID不能为空");
        }

        if (sessionManager.sessionExists(request.getId())) {
            sessionManager.clearSession(request.getId());
            return ResponseEntity.ok(ApiResponse.success("会话历史已清空"));
        } else {
            throw new ResourceNotFoundException("会话", request.getId());
        }
    }

    /**
     * 获取会话信息
     */
    @Operation(summary = "查询会话信息", description = "获取指定会话的历史消息对数和元数据")
    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        logger.info("收到获取会话信息请求 - SessionId: {}", sessionId);

        List<Map<String, String>> history = sessionManager.getHistoryOnly(sessionId);
        if (!history.isEmpty()) {
            SessionInfoResponse response = new SessionInfoResponse();
            response.setSessionId(sessionId);
            response.setMessagePairCount(history.size() / 2);
            response.setMessages(history);
            return ResponseEntity.ok(ApiResponse.success(response));
        }

        SessionManager.SessionMeta meta = sessionManager.getSessionMeta(sessionId);
        if (meta != null) {
            SessionInfoResponse response = new SessionInfoResponse();
            response.setSessionId(sessionId);
            response.setMessagePairCount(meta.getMessagePairCount());
            response.setCreateTime(meta.getCreateTime());
            return ResponseEntity.ok(ApiResponse.success(response));
        } else {
            throw new ResourceNotFoundException("会话", sessionId);
        }
    }

    // ==================== AIOps 路由处理（私有方法） ====================

    /**
     * AIOps 路由处理（同步模式，用于 /chat 端点）
     */
    private ResponseEntity<ApiResponse<ChatResponse>> handleAIOpsRoute(ChatRequest request) {
        logger.info("[AIOps Route] 路由到 AIOps 分析流程 - Question: {}", request.getQuestion());

        AiOpsResult result = aiOpsService.executeAiOpsAnalysis();

        if (!result.isSuccess() && result.getFinalReport() == null) {
            return ResponseEntity.ok(ApiResponse.success(
                    ChatResponse.error(result.getErrorMessage() != null
                            ? result.getErrorMessage()
                            : "AIOps 分析未能获取有效结果，请稍后重试")));
        }

        Optional<String> reportOptional = aiOpsService.extractFinalReport(result);
        if (reportOptional.isPresent()) {
            return ResponseEntity.ok(ApiResponse.success(
                    ChatResponse.success(reportOptional.get(), request.getId())));
        } else {
            return ResponseEntity.ok(ApiResponse.success(
                    ChatResponse.error("AIOps 流程已完成，但未能生成最终报告")));
        }
    }

    /**
     * AIOps 路由处理（SSE 流式模式，用于 /chat_stream 端点）
     */
    private void handleAIOpsRouteStream(SseEmitter emitter) {
        executor.execute(() -> {
            try {
                logger.info("[AIOps Route SSE] 路由到 AIOps 流式分析流程");

                emitter.send(SseEmitter.event().name("message")
                        .data(AgentEvent.content("正在读取告警并拆解任务...\n"), MediaType.APPLICATION_JSON));

                AiOpsResult result = aiOpsService.executeAiOpsAnalysis();

                if (!result.isSuccess() && result.getFinalReport() == null) {
                    emitter.send(SseEmitter.event().name("message")
                            .data(AgentEvent.error(result.getErrorMessage() != null
                                    ? result.getErrorMessage()
                                    : "AIOps 分析未能获取有效结果"), MediaType.APPLICATION_JSON));
                    emitter.complete();
                    return;
                }

                Optional<String> reportOptional = aiOpsService.extractFinalReport(result);

                if (reportOptional.isPresent()) {
                    String report = reportOptional.get();
                    emitter.send(SseEmitter.event().name("message")
                            .data(AgentEvent.content("\n\n" + "=".repeat(60) + "\n"), MediaType.APPLICATION_JSON));
                    emitter.send(SseEmitter.event().name("message")
                            .data(AgentEvent.content("📋 **告警分析报告**\n\n"), MediaType.APPLICATION_JSON));

                    int chunkSize = 50;
                    for (int i = 0; i < report.length(); i += chunkSize) {
                        int end = Math.min(i + chunkSize, report.length());
                        emitter.send(SseEmitter.event().name("message")
                                .data(AgentEvent.content(report.substring(i, end)), MediaType.APPLICATION_JSON));
                    }

                    emitter.send(SseEmitter.event().name("message")
                            .data(AgentEvent.content("\n" + "=".repeat(60) + "\n\n"), MediaType.APPLICATION_JSON));
                } else {
                    emitter.send(SseEmitter.event().name("message")
                            .data(AgentEvent.content("⚠️ 流程已完成，但未能生成最终报告。"), MediaType.APPLICATION_JSON));
                }

                emitter.send(SseEmitter.event().name("message").data(AgentEvent.done(null), MediaType.APPLICATION_JSON));
                emitter.complete();
                logger.info("[AIOps Route SSE] 流式分析完成");

            } catch (Exception e) {
                logger.error("[AIOps Route SSE] 分析失败", e);
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(AgentEvent.error("AIOps 分析失败: " + e.getMessage()), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });
    }

    /**
     * 从 SecurityContext 获取当前用户 ID
     */
    private String getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            return auth.getName();
        }
        return "anonymous";
    }
}
