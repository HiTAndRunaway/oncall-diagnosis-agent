package org.example;

import org.example.config.DashScopeApiProperties;
import org.example.config.LiteLlmProperties;
import org.example.config.ModelProperties;
import org.example.config.PromptProperties;
import org.example.config.SkillsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({ModelProperties.class, DashScopeApiProperties.class, PromptProperties.class, SkillsProperties.class, LiteLlmProperties.class})
public class Main {
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}