package org.example.agent.eval;

import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Loads AIOps test cases from {@code aiops-test-cases/} directory.
 * <p>
 * Each test case is a {@code .md} file with YAML frontmatter delimited by
 * {@code ---}. The frontmatter defines expected root causes, critical evidence,
 * mock alerts/logs, and the minimum pass score.
 */
@Component
public class TestCaseLoader {

    private static final Logger logger = LoggerFactory.getLogger(TestCaseLoader.class);

    private static final String TEST_CASES_DIR = "aiops-test-cases";

    private static final String FRONTMATTER_DELIMITER = "---";

    private final Yaml yaml = new Yaml();

    /**
     * Test case metadata parsed from YAML frontmatter.
     */
    @Getter
    @Setter
    public static class TestCaseMeta {

        private String id;
        private String severity;
        private List<String> expectedRootCauses = new ArrayList<>();
        private List<String> criticalEvidence = new ArrayList<>();
        private List<String> mockAlerts = new ArrayList<>();
        private List<String> mockLogs = new ArrayList<>();
        private int minScore = 12;

    }

    /**
     * Load a single test case by scenario ID.
     *
     * @param scenarioId the test case ID (matches the filename stem, e.g. "cpu-high-usage")
     * @return parsed metadata, or null if not found
     */
    public TestCaseMeta loadTestCase(String scenarioId) {
        if (scenarioId == null || scenarioId.isBlank()) {
            logger.warn("TestCaseLoader.loadTestCase called with null/blank scenarioId");
            return null;
        }

        // Prevent path traversal: reject IDs containing directory separators
        if (scenarioId.contains("/") || scenarioId.contains("\\") || scenarioId.contains("..")) {
            logger.warn("TestCaseLoader.loadTestCase: rejected potentially malicious scenarioId: {}", scenarioId);
            return null;
        }

        Path baseDir = Paths.get(TEST_CASES_DIR).toAbsolutePath().normalize();
        Path filePath = baseDir.resolve(scenarioId + ".md").normalize();

        // Ensure resolved path stays within the test cases directory
        if (!filePath.startsWith(baseDir)) {
            logger.warn("TestCaseLoader.loadTestCase: path traversal blocked for scenarioId: {}", scenarioId);
            return null;
        }

        if (!Files.exists(filePath)) {
            logger.warn("Test case file not found: {}", filePath.toAbsolutePath());
            return null;
        }

        return parseTestCaseFile(filePath);
    }

    /**
     * Load all test cases from the test cases directory.
     *
     * @return list of parsed test case metadata (empty if directory missing or empty)
     */
    public List<TestCaseMeta> loadAllTestCases() {
        Path dir = Paths.get(TEST_CASES_DIR);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            logger.warn("Test cases directory not found: {}", dir.toAbsolutePath());
            return Collections.emptyList();
        }

        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .map(this::parseTestCaseFile)
                    .filter(meta -> meta != null)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            logger.error("Failed to list test case files in {}", dir.toAbsolutePath(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Parse a single .md test case file, extracting YAML frontmatter.
     */
    private TestCaseMeta parseTestCaseFile(Path filePath) {
        try {
            String content = Files.readString(filePath);
            String frontmatter = extractFrontmatter(content);
            if (frontmatter == null) {
                logger.warn("No YAML frontmatter found in {}", filePath.getFileName());
                return null;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> data = yaml.load(frontmatter);
            if (data == null) {
                logger.warn("Empty frontmatter in {}", filePath.getFileName());
                return null;
            }

            TestCaseMeta meta = new TestCaseMeta();

            // id — use the "id" field from frontmatter, fallback to filename stem
            Object idObj = data.get("id");
            if (idObj instanceof String && !((String) idObj).isBlank()) {
                meta.setId((String) idObj);
            } else {
                String filename = filePath.getFileName().toString();
                meta.setId(filename.substring(0, filename.lastIndexOf('.')));
            }

            // severity
            Object severityObj = data.get("severity");
            if (severityObj instanceof String) {
                meta.setSeverity((String) severityObj);
            }

            // expected_root_causes
            Object rootCausesObj = data.get("expected_root_causes");
            if (rootCausesObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> list = (List<String>) rootCausesObj;
                meta.setExpectedRootCauses(list);
            }

            // critical_evidence
            Object evidenceObj = data.get("critical_evidence");
            if (evidenceObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> list = (List<String>) evidenceObj;
                meta.setCriticalEvidence(list);
            }

            // mock_alerts
            Object alertsObj = data.get("mock_alerts");
            if (alertsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> list = (List<String>) alertsObj;
                meta.setMockAlerts(list);
            }

            // mock_logs
            Object logsObj = data.get("mock_logs");
            if (logsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> list = (List<String>) logsObj;
                meta.setMockLogs(list);
            }

            // min_score
            Object minScoreObj = data.get("min_score");
            if (minScoreObj instanceof Number) {
                meta.setMinScore(((Number) minScoreObj).intValue());
            }

            logger.info("Loaded test case: id={}, severity={}", meta.getId(), meta.getSeverity());
            return meta;

        } catch (IOException e) {
            logger.error("Failed to read test case file: {}", filePath, e);
            return null;
        } catch (Exception e) {
            logger.error("Failed to parse test case file: {}", filePath, e);
            return null;
        }
    }

    /**
     * Extract YAML frontmatter content between {@code ---} delimiters.
     *
     * @param content full file content
     * @return frontmatter string (without delimiters), or null if no frontmatter found
     */
    private String extractFrontmatter(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }

        String trimmed = content.stripLeading();
        if (!trimmed.startsWith(FRONTMATTER_DELIMITER)) {
            return null;
        }

        int firstDelimEnd = trimmed.indexOf('\n');
        if (firstDelimEnd < 0) {
            return null;
        }

        String afterFirst = trimmed.substring(firstDelimEnd + 1);
        int secondDelim = afterFirst.indexOf("\n" + FRONTMATTER_DELIMITER);
        if (secondDelim < 0) {
            // try exact "---" at the start
            secondDelim = afterFirst.indexOf(FRONTMATTER_DELIMITER);
        }

        if (secondDelim <= 0) {
            return null;
        }

        return afterFirst.substring(0, secondDelim).trim();
    }

}
