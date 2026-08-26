package org.example.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@ConfigurationProperties(prefix = "prompts")
public class PromptProperties {
    private String defaultLang = "zh";
    private Map<String, String> mappings = new HashMap<>();
}
