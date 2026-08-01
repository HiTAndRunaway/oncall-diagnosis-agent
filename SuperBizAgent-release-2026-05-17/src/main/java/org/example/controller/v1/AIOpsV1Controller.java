package org.example.controller.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.dto.AgentEvent;
import org.example.dto.AiOpsResult;
import org.example.service.AiOpsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AIOps 智能运维 V1 控制器
 * 提供告警自动分析与诊断接口
 */
@Tag(name = "智能运维", description = "AIOps 告警自动分析与诊断")
@RestController
@RequestMapping("/api/v1")
public class AIOpsV1Controller {

    private static final Logger logger = LoggerFactory.getLogger(AIOpsV1Controller.class);

    @Autowired
    private AiOpsService aiOpsService;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * AI 智能运维接口（SSE 流式模式）
     * 自动分析告警并生成运维报告，无需用户输入
     */
    @Operation(summary = "AIOps 告警分析", description = "启动多 Agent 协作流程，自动分析告警并生成运维报告（SSE 流式输出）")
    @PostMapping(value = "/ai_ops", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter aiOps() {
        SseEmitter emitter = new SseEmitter(600000L); // 10分钟超时

        executor.execute(() -> {
            try {
                logger.info("收到 AI 智能运维请求 - 启动多 Agent 协作流程");

                emitter.send(SseEmitter.event().name("message")
                        .data(AgentEvent.content("正在读取告警并拆解任务...\n")));

                AiOpsResult result = aiOpsService.executeAiOpsAnalysis();

                if (!result.isSuccess() && result.getFinalReport() == null) {
                    emitter.send(SseEmitter.event().name("message")
                            .data(AgentEvent.error(result.getErrorMessage() != null
                                    ? result.getErrorMessage()
                                    : "多 Agent 编排未获取到有效结果"), MediaType.APPLICATION_JSON));
                    emitter.complete();
                    return;
                }

                logger.info("AI Ops 编排完成，开始提取最终报告...");

                Optional<String> finalReportOptional = aiOpsService.extractFinalReport(result);

                if (finalReportOptional.isPresent()) {
                    String finalReportText = finalReportOptional.get();
                    logger.info("提取到最终报告，长度: {}", finalReportText.length());

                    emitter.send(SseEmitter.event().name("message")
                            .data(AgentEvent.content("\n\n" + "=".repeat(60) + "\n"), MediaType.APPLICATION_JSON));

                    emitter.send(SseEmitter.event().name("message")
                            .data(AgentEvent.content("📋 **告警分析报告**\n\n"), MediaType.APPLICATION_JSON));

                    int chunkSize = 50;
                    for (int i = 0; i < finalReportText.length(); i += chunkSize) {
                        int end = Math.min(i + chunkSize, finalReportText.length());
                        String chunk = finalReportText.substring(i, end);
                        emitter.send(SseEmitter.event().name("message")
                                .data(AgentEvent.content(chunk), MediaType.APPLICATION_JSON));
                    }

                    emitter.send(SseEmitter.event().name("message")
                            .data(AgentEvent.content("\n" + "=".repeat(60) + "\n\n"), MediaType.APPLICATION_JSON));

                    logger.info("最终报告已完整输出");
                } else {
                    logger.warn("未能提取到最终报告");
                    emitter.send(SseEmitter.event().name("message")
                            .data(AgentEvent.content("⚠️ 多 Agent 流程已完成，但未能生成最终报告。"), MediaType.APPLICATION_JSON));
                }

                emitter.send(SseEmitter.event().name("message").data(AgentEvent.done(null), MediaType.APPLICATION_JSON));
                emitter.complete();
                logger.info("AI Ops 多 Agent 编排完成");

            } catch (Exception e) {
                logger.error("AI Ops 多 Agent 协作失败", e);
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(AgentEvent.error("AI Ops 流程失败: " + e.getMessage()), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}
