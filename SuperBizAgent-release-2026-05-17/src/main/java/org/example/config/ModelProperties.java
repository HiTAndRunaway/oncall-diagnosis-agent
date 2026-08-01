package org.example.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 分层模型配置属性
 * 为每个任务场景提供独立的模型名称和参数配置
 */
@ConfigurationProperties(prefix = "ai.model")
@Validated
public class ModelProperties {

    @NotNull @Valid
    private ModelConfig chat = new ModelConfig("qwen3-max", 0.7, 2000, 0.9);

    @NotNull @Valid
    private AiopsModels aiops = new AiopsModels();

    @NotNull @Valid
    private ModelConfig lightweight = new ModelConfig("qwen-turbo", 0.3, 2000, 0.9);

    @NotNull @Valid
    private ModelConfig reasoning = new ModelConfig("qwen3-max", 0.3, 4000, 0.9);

    @NotNull @Valid
    private ModelConfig rewrite = new ModelConfig("qwen-turbo", 0.3, 500, 0.9);

    // getters and setters
    public ModelConfig getChat() { return chat; }
    public void setChat(ModelConfig chat) { this.chat = chat; }
    public AiopsModels getAiops() { return aiops; }
    public void setAiops(AiopsModels aiops) { this.aiops = aiops; }
    public ModelConfig getLightweight() { return lightweight; }
    public void setLightweight(ModelConfig lightweight) { this.lightweight = lightweight; }
    public ModelConfig getReasoning() { return reasoning; }
    public void setReasoning(ModelConfig reasoning) { this.reasoning = reasoning; }
    public ModelConfig getRewrite() { return rewrite; }
    public void setRewrite(ModelConfig rewrite) { this.rewrite = rewrite; }

    /**
     * 单个模型配置
     */
    public static class ModelConfig {
        @NotBlank(message = "模型名称不能为空")
        private String name;

        @DecimalMin(value = "0.0", message = "temperature 不能小于 0.0")
        @DecimalMax(value = "2.0", message = "temperature 不能大于 2.0")
        private double temperature;

        @Min(value = 1, message = "maxToken 最小为 1")
        @Max(value = 32768, message = "maxToken 最大为 32768")
        private int maxToken;

        @DecimalMin(value = "0.0", message = "topP 不能小于 0.0")
        @DecimalMax(value = "1.0", message = "topP 不能大于 1.0")
        private double topP;

        public ModelConfig() {}

        public ModelConfig(String name, double temperature, int maxToken, double topP) {
            this.name = name;
            this.temperature = temperature;
            this.maxToken = maxToken;
            this.topP = topP;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getMaxToken() { return maxToken; }
        public void setMaxToken(int maxToken) { this.maxToken = maxToken; }
        public double getTopP() { return topP; }
        public void setTopP(double topP) { this.topP = topP; }
    }

    /**
     * AIOps 三 Agent 独立模型配置
     */
    public static class AiopsModels {
        @NotNull @Valid
        private ModelConfig supervisor = new ModelConfig("qwen3-max", 0.3, 8000, 0.9);

        @NotNull @Valid
        private ModelConfig planner = new ModelConfig("qwen3-max", 0.3, 8000, 0.9);

        @NotNull @Valid
        private ModelConfig executor = new ModelConfig("qwen-turbo", 0.3, 4000, 0.9);

        public ModelConfig getSupervisor() { return supervisor; }
        public void setSupervisor(ModelConfig supervisor) { this.supervisor = supervisor; }
        public ModelConfig getPlanner() { return planner; }
        public void setPlanner(ModelConfig planner) { this.planner = planner; }
        public ModelConfig getExecutor() { return executor; }
        public void setExecutor(ModelConfig executor) { this.executor = executor; }
    }
}
