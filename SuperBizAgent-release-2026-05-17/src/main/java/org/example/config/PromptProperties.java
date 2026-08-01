package org.example.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "prompts")
public class PromptProperties {
    private String defaultLang = "zh";
    private Map<String, String> mappings = new HashMap<>();
    public String getDefaultLang() { return defaultLang; }
    public void setDefaultLang(String defaultLang) { this.defaultLang = defaultLang; }
    public Map<String, String> getMappings() { return mappings; }
    public void setMappings(Map<String, String> mappings) { this.mappings = mappings; }
}
