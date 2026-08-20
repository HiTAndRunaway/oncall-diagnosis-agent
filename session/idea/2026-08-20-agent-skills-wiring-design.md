# Agent Skills 预留接线设计（对话中创建的 agent 使用 skills）

- 日期：2026-08-20
- 状态：设计定稿（待确认后实施）
- 前置：已完成 spring-ai-alibaba 1.1.2.0 升级（首个支持 Agent Skills 的稳定版）
- 已核实的技术事实（解包 1.1.2.0 jar 验证）：
  - `com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry`（接口，graph-core）
    - `FileSystemSkillRegistry`（builder: projectSkillsDirectory/userSkillsDirectory，支持 reload 热更新）
    - `ClasspathSkillRegistry`（builder: classpathPath/basePath，随 jar 打包）
  - `com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook`（agent-framework）
    - Builder: `registry(SkillRegistry)`、`groupedTools(Map)`（技能→工具绑定，渐进式披露）、`build()`
    - 行为：技能清单（name/description/path）追加式注入 system prompt + 注册 `read_skill(skill_name)` 工具
    - **不依赖 saver**，兼容本项目"每次对话新建 agent、无状态"模式
  - `ReactAgent.Builder.hooks(List)`：存在，且 DefaultBuilder 对空列表有 isEmpty 保护，传空列表安全
  - 技能目录结构：`skill-name/SKILL.md`（frontmatter: name/description）+ 可选 references/examples/scripts

## 1. 目标与范围

- 目标：在项目创建 agent 的代码处**预留** Skills 接线——默认关闭（零行为变化），翻配置即可启用
- 范围（用户已确认）：
  - 默认开关 `skills.enabled=false`（纯预留）
  - registry 支持 classpath（默认）与 filesystem 配置切换
  - 覆盖**全部 3 个 ReactAgent**：对话 agent（buildReactAgent）+ AIOps planner/executor
  - 暂不接线 groupedTools（第一阶段只做知识型技能）
  - 内置 1 个示例技能（运维处置手册）

## 2. 配置（application.yml 新增）

```yaml
# Agent Skills 配置（spring-ai-alibaba 1.1.2.0 能力；false=纯预留，行为与现状完全一致）
skills:
  enabled: false                 # 总开关
  registry: classpath            # classpath | filesystem
  classpath-path: skills         # registry=classpath 时的资源目录（默认 src/main/resources/skills）
  project-dir: skills            # registry=filesystem 时的项目级目录
  user-dir:                      # registry=filesystem 时的用户级目录（留空=框架默认 ~/saa/skills）
```

## 3. 新增文件

### 3.1 `config/SkillsProperties.java`
仿 `AgenticRagProperties` 的 `@ConfigurationProperties(prefix = "skills")`：
- `boolean enabled = false`
- `enum RegistryType { CLASSPATH, FILESYSTEM }`，`RegistryType registry = CLASSPATH`
- `String classpathPath = "skills"` / `String projectDir = "skills"` / `String userDir`（可空）
- `@EnableConfigurationProperties` 在 `Main.java` 注册（与 ModelProperties 等一致）

### 3.2 `config/SkillsConfig.java`
```java
@Configuration
@ConditionalOnProperty(prefix = "skills", name = "enabled", havingValue = "true")
public class SkillsConfig {

    @Bean
    public SkillRegistry skillRegistry(SkillsProperties props) {
        if (props.getRegistry() == RegistryType.FILESYSTEM) {
            var b = FileSystemSkillRegistry.builder()
                    .projectSkillsDirectory(props.getProjectDir());
            if (props.getUserDir() != null && !props.getUserDir().isBlank()) {
                b.userSkillsDirectory(props.getUserDir());
            }
            return b.build();
        }
        return ClasspathSkillRegistry.builder()
                .classpathPath(props.getClasspathPath())
                .build();
    }

    @Bean
    public SkillsAgentHook skillsAgentHook(SkillRegistry registry) {
        return SkillsAgentHook.builder().registry(registry).build();
    }
}
```
- 关闭时两个 bean **完全不注册**（@ConditionalOnProperty），与 rag.agentic 工具模式一致

### 3.3 示例技能 `src/main/resources/skills/oncall-runbook/SKILL.md`
- frontmatter: `name: oncall-runbook` + description（何时使用）
- 正文：运维处置手册知识（与 aiops-docs 呼应：CPU/内存/磁盘/服务不可用/慢响应），供 read_skill 读取
- 可选 `references/` 子文件 1 个，演示渐进式加载
- 附带 `src/main/resources/skills/README.md`：说明技能目录格式与接入方式

## 4. 修改文件

### 4.1 `agent/ReactAgentRunner.java`
- 新增注入：`@Autowired(required = false) private SkillsAgentHook skillsAgentHook;`
- 新增私有方法：
  ```java
  private List<AgentHook> buildHooks() {
      return skillsAgentHook != null ? List.of(skillsAgentHook) : List.of();
  }
  ```
- 三个 builder 各加一行 `.hooks(buildHooks())`：
  - `buildReactAgent()`（对话 agent）
  - `buildPlannerAgent()`（AIOps planner）
  - `buildExecutorAgent()`（AIOps executor）
- SupervisorAgent 不动（调度层，子 agent 已带 hooks）
- 关闭时 `buildHooks()` 返回空列表，与现有行为完全一致（DefaultBuilder 已处理空列表）

### 4.2 `src/main/resources/application.yml`
新增第 2 节 `skills:` 配置块

### 4.3 `src/main/java/org/example/Main.java`
`@EnableConfigurationProperties` 加入 `SkillsProperties.class`

### 4.4 测试
- `src/test/resources/application-test.yml`：`skills.enabled: true` + registry=classpath
  → 冒烟测试的上下文将覆盖**启用路径**（验证 hook/registry bean 注入不破坏上下文）
- 新增 `src/test/java/org/example/SkillsWiringTest.java`（不依赖网络/LLM）：
  - ClasspathSkillRegistry 能列出 `oncall-runbook` 示例技能
  - SkillsAgentHook 构建成功且 read_skill 工具回调存在（通过 ReadSkillTool 或 hook 装配验证）
  - SkillsProperties 默认值绑定正确（enabled=false, registry=classpath）

## 5. 兼容性与风险

| 项 | 结论 |
|----|------|
| 默认关闭 | enabled=false 时 SkillsConfig 不生效、buildHooks 返回空 → 行为与升级前完全一致 |
| system prompt | 技能清单为追加式段落，不覆盖 PromptManager 渲染的 system-prompt.md，与 agentic-rag 指令共存 |
| 流式事件 | hooks 追加 read_skill 工具，executeStream 的 AgentEvent 桥接不受影响（工具事件原样透传） |
| 无状态模式 | SkillsAgentHook 不依赖 saver，无需引入 MemorySaver |
| AIOps 模板 | planner/executor 的系统提示为追加技能清单 + 新增可用工具（非强制），不影响 FINISH 报告模板 |
| 开启但无技能目录 | ClasspathSkillRegistry 指向不存在的路径需验证行为（预期返回空列表）；示例技能目录保证 jar 内始终存在该路径 |
| groupedTools | 本次不接线，后续可扩展 `skills.<name>.tools` 配置 |

## 6. 实施步骤（按 CLAUDE.md 流程）

1. 当前分支 `feature/spring-ai-alibaba-1.1.2.0-upgrade` 基础上新建 `feature/agent-skills-wiring`
2. 新增 SkillsProperties/SkillsConfig/示例技能，修改 ReactAgentRunner/application.yml/Main
3. 新增 SkillsWiringTest，跑 `mvn clean install`（编译 + 测试全绿）
4. 上下文冒烟验证（skills.enabled=true 路径 + 默认关闭路径各一次）
5. code review（读 ~/.claude/skills/code-review-expert 或独立子代理）
6. 修复审查问题 → 再测 → 提交推送（重试 5 次，失败停止）
