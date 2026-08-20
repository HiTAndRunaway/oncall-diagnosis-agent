package org.example;

import com.alibaba.cloud.ai.graph.agent.hook.skills.ReadSkillTool;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.classpath.ClasspathSkillRegistry;
import org.example.config.SkillsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent Skills 接线单元测试（不依赖网络/LLM）
 * <p>
 * 验证：classpath 技能注册表能发现示例技能、SkillsAgentHook 可装配、
 * read_skill 工具回调存在、SkillsProperties 默认值正确。
 */
class SkillsWiringTest {

    /**
     * 构造 classpath registry：显式指定 basePath（系统临时目录），
     * 避免框架默认 basePath（本机解析为 D:\tmp）不可写导致复制技能资源失败。
     */
    private static SkillRegistry newClasspathRegistry() {
        return ClasspathSkillRegistry.builder()
                .classpathPath("skills")
                .basePath(System.getProperty("java.io.tmpdir"))
                .build();
    }

    @Test
    void classpathRegistry_shouldListSampleSkill() {
        SkillRegistry registry = newClasspathRegistry();

        List<SkillMetadata> skills = registry.listAll();

        assertNotNull(skills);
        assertFalse(skills.isEmpty(), "classpath:skills 下应至少有一个技能");
        assertTrue(skills.stream().anyMatch(s -> "oncall-runbook".equals(s.getName())),
                "应能发现示例技能 oncall-runbook");
    }

    @Test
    void skillsAgentHook_shouldBuildWithRegistry() {
        SkillRegistry registry = newClasspathRegistry();

        SkillsAgentHook hook = SkillsAgentHook.builder()
                .skillRegistry(registry)
                .build();

        assertNotNull(hook);
    }

    @Test
    void readSkillTool_shouldBeExposed() {
        SkillRegistry registry = newClasspathRegistry();

        ToolCallback callback = ReadSkillTool.createReadSkillToolCallback(registry, "read_skill");

        assertNotNull(callback);
        assertEquals("read_skill", callback.getToolDefinition().name());
        assertNotNull(callback.getToolDefinition().description());
    }

    @Test
    void skillsProperties_shouldHaveDefaults() {
        SkillsProperties props = new SkillsProperties();

        assertFalse(props.isEnabled());
        assertEquals(SkillsProperties.RegistryType.CLASSPATH, props.getRegistry());
        assertEquals("skills", props.getClasspathPath());
        assertEquals("skills", props.getProjectDir());
    }
}
