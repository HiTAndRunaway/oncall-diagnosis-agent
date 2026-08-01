package org.example.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 聊天请求 DTO
 */
@Schema(description = "聊天请求")
public class ChatRequest {

    @Schema(description = "会话ID", example = "abc123-def456")
    @JsonProperty(value = "Id")
    @JsonAlias({"id", "ID"})
    private String Id;

    @Schema(description = "用户问题", example = "当前系统有哪些活跃告警？")
    @JsonProperty(value = "Question")
    @JsonAlias({"question", "QUESTION"})
    @NotBlank(message = "问题内容不能为空")
    private String Question;

    public String getId() {
        return Id;
    }

    public void setId(String Id) {
        this.Id = Id;
    }

    public String getQuestion() {
        return Question;
    }

    public void setQuestion(String Question) {
        this.Question = Question;
    }
}
