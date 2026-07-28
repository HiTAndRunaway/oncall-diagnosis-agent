package org.example.agent.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 测试用例加载器
 * 解析 aiops-test-cases/ 下 .md 文件的 YAML frontmatter
 */
@Component
public class TestCaseLoader {

    private static final Logger logger = LoggerFactory.getLogger(TestCaseLoader.class);
    private static final String TEST_CASES_DIR = "aiops-test-cases";

    private final Yaml yaml = new Yaml();

    /**
     * 加载单个测试用例
     *
     * @param scenarioId 场景 ID（不含 .md 扩展名）
     * @return TestCaseMeta 或 null（不存在时）
     */
    public TestCaseMeta loadTestCase(String scenarioId) {
        Path filePath = Paths.get(TEST_CASES_DIR, scenarioId + ".md");
        if (!Files.exists(filePath)) {
            logger.warn("[TestCaseLoader] 测试用例不存在: {}", filePath);
            return null;
        }
        try {
            return parseTestCaseFile(filePath);
        } catch (IOException e) {
            logger.warn("[TestCaseLoader] 加载测试用例失败: {} - {}", filePath, e.getMessage());
            return null;
        }
    }

    /**
     * 加载所有测试用例
     */
    public List<TestCaseMeta> loadAllTestCases() {
        List<TestCaseMeta> result = new ArrayList<>();
        Path dirPath = Paths.get(TEST_CASES_DIR);
        if (!Files.isDirectory(dirPath)) {
            logger.warn("[TestCaseLoader] 测试用例目录不存在: {}", dirPath);
            return result;
        }
        try (var files = Files.list(dirPath)) {
            List<Path> mdFiles = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md"))
                    .collect(Collectors.toList());

            for (Path file : mdFiles) {
                try {
                    TestCaseMeta meta = parseTestCaseFile(file);
                    if (meta != null) {
                        result.add(meta);
                    }
                } catch (Exception e) {
                    logger.warn("[TestCaseLoader] 跳过损坏的测试用例: {} - {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.warn("[TestCaseLoader] 列举测试用例目录失败", e);
        }
        logger.info("[TestCaseLoader] 加载了 {} 个测试用例", result.size());
        return result;
    }

    /**
     * 解析单个 .md 文件的 YAML frontmatter
     */
    @SuppressWarnings("unchecked")
    private TestCaseMeta parseTestCaseFile(Path filePath) throws IOException {
        StringBuilder frontmatter = new StringBuilder();
        boolean inFrontmatter = false;
        int dashes = 0;

        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().equals("---")) {
                    dashes++;
                    if (dashes == 1) {
                        inFrontmatter = true;
                        continue;
                    } else if (dashes == 2) {
                        break;
                    }
                }
                if (inFrontmatter) {
                    frontmatter.append(line).append("\n");
                }
            }
        }

        if (frontmatter.isEmpty()) {
            logger.warn("[TestCaseLoader] 文件无 frontmatter: {}", filePath);
            return null;
        }

        Map<String, Object> map = yaml.load(frontmatter.toString());

        TestCaseMeta meta = new TestCaseMeta();
        meta.setId((String) map.get("id"));
        meta.setSeverity((String) map.get("severity"));
        meta.setExpectedRootCauses(safeCastList(map.get("expected_root_causes")));
        meta.setCriticalEvidence(safeCastList(map.get("critical_evidence")));
        meta.setMockAlerts(safeCastList(map.get("mock_alerts")));
        meta.setMockLogs(safeCastList(map.get("mock_logs")));

        Object minScoreObj = map.get("min_score");
        if (minScoreObj instanceof Number) {
            meta.setMinScore(((Number) minScoreObj).intValue());
        }

        return meta;
    }

    @SuppressWarnings("unchecked")
    private List<String> safeCastList(Object obj) {
        if (obj instanceof List) {
            return (List<String>) obj;
        }
        return List.of();
    }
}
