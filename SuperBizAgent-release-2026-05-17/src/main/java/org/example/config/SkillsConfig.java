package org.example.config;

import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.classpath.ClasspathSkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent Skills 装配（spring-ai-alibaba 1.1.2.0）
 * <p>
 * 仅在 skills.enabled=true 时注册：
 * <ul>
 *   <li>{@link SkillRegistry}：按配置选择 Classpath（随 jar 打包）或 FileSystem（项目/用户目录，可热更新）实现</li>
 *   <li>{@link SkillsAgentHook}：向 Agent 注入技能清单（name/description/path）并注册 read_skill 工具</li>
 * </ul>
 * 关闭时本类整体不生效，ReactAgentRunner 的 hooks 为空，行为与升级前完全一致。
 */
@Configuration
@ConditionalOnProperty(prefix = "skills", name = "enabled", havingValue = "true")
public class SkillsConfig {

    @Bean
    public SkillRegistry skillRegistry(SkillsProperties props) {
        if (props.getRegistry() == SkillsProperties.RegistryType.FILESYSTEM) {
            FileSystemSkillRegistry.Builder builder = FileSystemSkillRegistry.builder()
                    .projectSkillsDirectory(props.getProjectDir());
            if (props.getUserDir() != null && !props.getUserDir().isBlank()) {
                builder.userSkillsDirectory(props.getUserDir());
            }
            return builder.build();
        }
        // basePath：classpath 技能资源复制到的本地目录（默认系统临时目录，避免框架默认 D:\tmp 不可写）
        String basePath = props.getClasspathBasePath() != null && !props.getClasspathBasePath().isBlank()
                ? props.getClasspathBasePath()
                : System.getProperty("java.io.tmpdir");
        return ClasspathSkillRegistry.builder()
                .classpathPath(props.getClasspathPath())
                .basePath(basePath)
                .build();
    }

    @Bean
    public SkillsAgentHook skillsAgentHook(SkillRegistry skillRegistry, SkillsProperties props) {
        return SkillsAgentHook.builder()
                .skillRegistry(skillRegistry)
                .autoReload(props.isAutoReload())
                .build();
    }
}
