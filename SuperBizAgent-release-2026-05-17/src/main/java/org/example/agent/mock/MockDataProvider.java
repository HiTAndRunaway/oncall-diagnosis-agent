package org.example.agent.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Mock data provider for AIOps test scenarios.
 * <p>
 * Only registered when the {@code test} Spring profile is active.
 * Loads mock alert and log JSON files from {@code aiops-test-cases/mock-data/}.
 */
@Component
@Profile("test")
public class MockDataProvider {

    private static final Logger logger = LoggerFactory.getLogger(MockDataProvider.class);

    private static final String MOCK_DATA_DIR = "aiops-test-cases/mock-data";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Get mock alert data for a given scenario.
     * <p>
     * Looks for a file named {@code <scenarioId>-alerts.json} in the
     * {@code aiops-test-cases/mock-data/} directory.
     *
     * @param scenarioId the test scenario identifier
     * @return alert JSON string matching QueryMetricsTools output format,
     *         or a fallback JSON if the file is not found
     */
    public String getMockAlerts(String scenarioId) {
        return loadMockDataFile(scenarioId, "alerts");
    }

    /**
     * Get mock log data for a given scenario and log topic.
     * <p>
     * Looks for a file named {@code <scenarioId>-logs-<logTopic>.json} in the
     * {@code aiops-test-cases/mock-data/} directory. If not found, falls back
     * to {@code <scenarioId>-logs.json}.
     *
     * @param scenarioId the test scenario identifier
     * @param logTopic the log topic (e.g. "system-metrics")
     * @return log JSON string matching QueryLogsTools output format,
     *         or a fallback JSON if the file is not found
     */
    public String getMockLogs(String scenarioId, String logTopic) {
        // Try topic-specific file first
        if (logTopic != null && !logTopic.isBlank()) {
            String data = loadMockDataFile(scenarioId, "logs-" + logTopic);
            if (data != null && !data.contains("\"status\": \"no_mock_data\"")) {
                return data;
            }
        }

        // Fallback to generic logs file
        return loadMockDataFile(scenarioId, "logs");
    }

    /**
     * Load a mock data JSON file from the mock-data directory.
     *
     * @param scenarioId the test scenario identifier
     * @param suffix the file name suffix (e.g. "alerts", "logs", "logs-system-metrics")
     * @return file content as string, or a fallback JSON if not found
     */
    private String loadMockDataFile(String scenarioId, String suffix) {
        if (scenarioId == null || scenarioId.isBlank()) {
            return buildNoDataResponse(scenarioId);
        }

        // Prevent path traversal in both scenarioId and suffix
        if (containsPathTraversal(scenarioId) || containsPathTraversal(suffix)) {
            logger.warn("MockDataProvider: rejected path traversal attempt — scenarioId={}, suffix={}", scenarioId, suffix);
            return buildNoDataResponse(scenarioId);
        }

        String filename = scenarioId + "-" + suffix + ".json";
        Path baseDir = Paths.get(MOCK_DATA_DIR).toAbsolutePath().normalize();
        Path filePath = baseDir.resolve(filename).normalize();

        // Ensure resolved path stays within the mock-data directory
        if (!filePath.startsWith(baseDir)) {
            logger.warn("MockDataProvider: path traversal blocked — filePath={}", filePath);
            return buildNoDataResponse(scenarioId);
        }

        if (!Files.exists(filePath)) {
            logger.debug("Mock data file not found: {}", filePath.toAbsolutePath());
            return buildNoDataResponse(scenarioId);
        }

        try {
            String content = Files.readString(filePath);
            logger.info("Loaded mock data from {}", filePath.getFileName());
            return content;
        } catch (IOException e) {
            logger.warn("Failed to read mock data file: {}", filePath, e.getMessage());
            return buildNoDataResponse(scenarioId);
        }
    }

    /**
     * Check if a string contains path traversal characters.
     */
    private boolean containsPathTraversal(String value) {
        if (value == null) {
            return false;
        }
        return value.contains("/") || value.contains("\\") || value.contains("..");
    }

    /**
     * Build a fallback response when no mock data is available.
     */
    private String buildNoDataResponse(String scenarioId) {
        try {
            Map<String, Object> response = Map.of(
                    "status", "no_mock_data",
                    "scenarioId", scenarioId != null ? scenarioId : "null"
            );
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"status\":\"no_mock_data\",\"scenarioId\":\"" + (scenarioId != null ? scenarioId : "null") + "\"}";
        }
    }

}
