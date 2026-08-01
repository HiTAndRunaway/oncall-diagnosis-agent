package org.example.service;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import jakarta.annotation.PostConstruct;
import org.example.config.PromptProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Prompt 管理器
 * 启动时加载 prompts/ 目录下的所有 .md 文件，支持 Mustache 模板渲染和中英文双语
 */
@Component
public class PromptManager {

    private static final Logger log = LoggerFactory.getLogger(PromptManager.class);

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$",
            Pattern.DOTALL);

    private final PromptProperties properties;
    private final Mustache.Compiler compiler;

    /** 已加载的 Prompt：key = "zh:chat/system-prompt" */
    private final Map<String, CompiledPrompt> prompts = new ConcurrentHashMap<>();

    public PromptManager(PromptProperties properties) {
        this.properties = properties;
        this.compiler = Mustache.compiler().defaultValue("");
    }

    @PostConstruct
    public void loadAll() {
        log.info("开始加载 Prompt 文件...");
        try {
            ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:prompts/**/*.md");
            for (Resource resource : resources) {
                loadSingle(resource);
            }
            log.info("Prompt 加载完成，共 {} 个模板", prompts.size());
        } catch (Exception e) {
            throw new IllegalStateException("Prompt 文件加载失败，应用无法启动", e);
        }
    }

    private void loadSingle(Resource resource) {
        try {
            String path = resource.getURL().getPath();
            // 解析路径: .../prompts/zh/chat/system-prompt.md → lang=zh, key=chat/system-prompt
            String relativePath = path.substring(path.indexOf("prompts/"));
            String withoutPrefix = relativePath.substring("prompts/".length()); // zh/chat/system-prompt.md
            String[] parts = withoutPrefix.split("/", 2);
            String lang = parts[0];                    // zh
            String key = parts[1].replace(".md", "");  // chat/system-prompt

            String content = readResource(resource);
            ParsedPrompt parsed = parseFrontmatter(content);

            Template template = compiler.compile(parsed.body);
            CompiledPrompt cp = new CompiledPrompt(parsed.meta, template, path);
            prompts.put(lang + ":" + key, cp);

            log.debug("已加载 Prompt: {}:{}", lang, key);
        } catch (Exception e) {
            log.error("加载 Prompt 失败: {}", resource.getFilename(), e);
            throw new IllegalStateException("无法加载 Prompt 文件: " + resource.getFilename(), e);
        }
    }

    private String readResource(Resource resource) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private ParsedPrompt parseFrontmatter(String content) {
        Matcher m = FRONTMATTER_PATTERN.matcher(content);
        if (m.find()) {
            PromptMeta meta = parseMeta(m.group(1));
            String body = m.group(2);
            return new ParsedPrompt(meta, body);
        }
        // 无 frontmatter → 整个文件作为模板正文
        return new ParsedPrompt(new PromptMeta(), content);
    }

    private PromptMeta parseMeta(String yamlBlock) {
        PromptMeta meta = new PromptMeta();
        for (String line : yamlBlock.split("\n")) {
            String[] kv = line.split(":", 2);
            if (kv.length < 2) continue;
            String key = kv[0].trim();
            String value = kv[1].trim();
            switch (key) {
                case "version": meta.setVersion(Integer.parseInt(value)); break;
                case "modified": meta.setModified(value); break;
                case "author": meta.setAuthor(value); break;
                case "changes": meta.setChanges(value); break;
                case "model": meta.setModel(value); break;
            }
        }
        return meta;
    }

    /**
     * 渲染 Prompt 模板
     *
     * @param key       Prompt 标识，如 "chat/system-prompt"
     * @param variables 模板变量
     * @param lang      语言代码 ("zh" / "en")，null 则使用默认语言
     * @return 渲染后的完整 Prompt 文本
     */
    public String render(String key, Map<String, Object> variables, String lang) {
        String langKey = resolveLang(lang);
        CompiledPrompt cp = resolvePrompt(key, langKey);
        try {
            return cp.template.execute(variables != null ? variables : Collections.emptyMap());
        } catch (Exception e) {
            log.error("Prompt 渲染失败: key={}, lang={}", key, langKey, e);
            throw new IllegalStateException(
                    "Prompt 渲染失败: key=" + key + ", lang=" + langKey + " - " + e.getMessage(), e);
        }
    }

    /**
     * 获取 Prompt 元数据
     */
    public PromptMeta getMeta(String key, String lang) {
        String langKey = resolveLang(lang);
        CompiledPrompt cp = resolvePrompt(key, langKey);
        return cp.meta;
    }

    private String resolveLang(String lang) {
        if (lang != null && !lang.isEmpty()) return lang;
        return properties.getDefaultLang();
    }

    private CompiledPrompt resolvePrompt(String key, String langKey) {
        // 1. 尝试请求语言
        CompiledPrompt cp = prompts.get(langKey + ":" + key);
        if (cp != null) return cp;

        // 2. 回退到默认语言
        String defaultLang = properties.getDefaultLang();
        if (!defaultLang.equals(langKey)) {
            cp = prompts.get(defaultLang + ":" + key);
            if (cp != null) {
                log.debug("Prompt '{}' 在语言 '{}' 下未找到，回退到 '{}'", key, langKey, defaultLang);
                return cp;
            }
        }

        // 3. 找不到 → 抛异常
        throw new IllegalStateException(
                "Prompt not found: key=" + key + ", lang=" + langKey);
    }

    // ===== 内部类型 =====

    private static class ParsedPrompt {
        final PromptMeta meta;
        final String body;
        ParsedPrompt(PromptMeta meta, String body) { this.meta = meta; this.body = body; }
    }

    private static class CompiledPrompt {
        final PromptMeta meta;
        final Template template;
        final String sourcePath;
        CompiledPrompt(PromptMeta meta, Template template, String sourcePath) {
            this.meta = meta; this.template = template; this.sourcePath = sourcePath;
        }
    }

    /**
     * Prompt 元数据（对应 YAML frontmatter）
     */
    public static class PromptMeta {
        private int version;
        private String modified;
        private String author;
        private String changes;
        private String model;

        public int getVersion() { return version; }
        public void setVersion(int version) { this.version = version; }
        public String getModified() { return modified; }
        public void setModified(String modified) { this.modified = modified; }
        public String getAuthor() { return author; }
        public void setAuthor(String author) { this.author = author; }
        public String getChanges() { return changes; }
        public void setChanges(String changes) { this.changes = changes; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }
}
