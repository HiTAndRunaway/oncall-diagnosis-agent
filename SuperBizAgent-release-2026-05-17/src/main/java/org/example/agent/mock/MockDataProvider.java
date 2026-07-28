package org.example.agent.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Mock 数据注入器
 * 仅在 test profile 激活时注册，从 aiops-test-cases/mock-data/ 加载场景化 Mock 数据
 */
@Component
@Profile("test")
public class MockDataProvider {

    private static final Logger logger = LoggerFactory.getLogger(MockDataProvider.class);
    private static final String MOCK_DATA_DIR = "aiops-test-cases/mock-data";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 获取指定场景的 Mock 告警数据（JSON 字符串）
     */
    @SuppressWarnings("unchecked")
    public String getMockAlerts(String scenarioId) {
        try {
            Map<String, Object> data = loadMockData(scenarioId);
            if (data == null) {
                return buildErrorResponse(scenarioId, "no_mock_data");
            }
            Map<String, Object> alerts = (Map<String, Object>) data.get("alerts");
            if (alerts == null || alerts.isEmpty()) {
                return buildErrorResponse(scenarioId, "no_alerts");
            }
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(alerts);
        } catch (Exception e) {
            logger.warn("[MockDataProvider] 获取告警数据失败: {} - {}", scenarioId, e.getMessage());
            return buildErrorResponse(scenarioId, "error: " + e.getMessage());
        }
    }

    /**
     * 获取指定场景的 Mock 日志数据（JSON 字符串）
     */
    @SuppressWarnings("unchecked")
    public String getMockLogs(String scenarioId, String logTopic) {
        try {
            Map<String, Object> data = loadMockData(scenarioId);
            if (data == null) {
                return buildErrorResponse(scenarioId, "no_mock_data");
            }
            Map<String, Object> logs = (Map<String, Object>) data.get("logs");
            if (logs == null) {
                return buildErrorResponse(scenarioId, "no_logs");
            }
            Object topicLogs = logs.get(logTopic);
            if (topicLogs == null) {
                return buildErrorResponse(scenarioId, "no_logs_topic: " + logTopic);
            }
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(topicLogs);
        } catch (Exception e) {
            logger.warn("[MockDataProvider] 获取日志数据失败: {} - {}", scenarioId, e.getMessage());
            return buildErrorResponse(scenarioId, "error: " + e.getMessage());
        }
    }

    /**
     * 获取所有可用的日志主题
     */
    @SuppressWarnings("unchecked")
    public String getAvailableTopics(String scenarioId) {
        try {
            Map<String, Object> data = loadMockData(scenarioId);
            if (data == null) {
                return "[]";
            }
            Map<String, Object> logs = (Map<String, Object>) data.get("logs");
            if (logs == null) {
                return "[]";
            }
            List<String> topics = logs.keySet().stream()
                    .map(k -> "{\"topic\": \"" + k + "\", \"description\": \"Mock log data for " + k + "\"}")
                    .toList();
            return "[" + String.join(",", topics) + "]";
        } catch (Exception e) {
            return "[]";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadMockData(String scenarioId) throws Exception {
        Path filePath = Paths.get(MOCK_DATA_DIR, scenarioId + "-data.json");
        if (!Files.exists(filePath)) {
            logger.warn("[MockDataProvider] Mock 数据文件不存在: {}", filePath);
            return null;
        }
        String json = Files.readString(filePath);
        return objectMapper.readValue(json, Map.class);
    }

    private String buildErrorResponse(String scenarioId, String error) {
        return "{\"status\": \"" + error + "\", \"scenarioId\": \"" + scenarioId + "\"}";
    }
}
