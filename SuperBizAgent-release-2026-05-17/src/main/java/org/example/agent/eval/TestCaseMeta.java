package org.example.agent.eval;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 测试用例元数据（从 .md 文件 YAML frontmatter 解析）
 */
@Getter
@Setter
public class TestCaseMeta {
    private String id;
    private String severity;
    private List<String> expectedRootCauses;
    private List<String> criticalEvidence;
    private List<String> mockAlerts;
    private List<String> mockLogs;
    private int minScore = 12;
}
