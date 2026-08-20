package org.example.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent Skills 配置属性
 * <p>
 * 绑定 application.yml 中 skills.* 配置块，控制 spring-ai-alibaba 1.1.2.0
 * Agent Skills（SkillRegistry + SkillsAgentHook）的开关与技能来源。
 * 默认关闭（enabled=false）：不注册任何 Skills Bean，行为与未引入 Skills 前完全一致。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "skills")
public class SkillsProperties {

    /** 全局开关，false 时不注册任何 Skills Bean */
    private boolean enabled = false;

    /** 技能注册表类型：classpath（随 jar 打包，默认）| filesystem（项目/用户目录，可热更新） */
    private RegistryType registry = RegistryType.CLASSPATH;

    /** registry=classpath 时的资源目录（默认 src/main/resources/skills） */
    private String classpathPath = "skills";

    /** registry=classpath 时技能资源复制到的本地目录（留空=系统临时目录，供 scripts 等附属文件落盘） */
    private String classpathBasePath = "";

    /** registry=filesystem 时的项目级技能目录 */
    private String projectDir = "skills";

    /** registry=filesystem 时的用户级技能目录（留空走框架默认 ~/saa/skills） */
    private String userDir = "";

    /** 技能热更新开关（filesystem 来源时有效），true 时 SkillsAgentHook 自动 reload 技能目录 */
    private boolean autoReload = false;

    public enum RegistryType {
        CLASSPATH, FILESYSTEM
    }
}
