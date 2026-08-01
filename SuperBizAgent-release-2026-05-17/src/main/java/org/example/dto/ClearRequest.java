package org.example.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 清空会话请求 DTO
 */
@Schema(description = "清空会话请求")
public class ClearRequest {

    @Schema(description = "会话ID", example = "abc123-def456")
    @JsonProperty(value = "Id")
    @JsonAlias({"id", "ID"})
    private String Id;

    public String getId() {
        return Id;
    }

    public void setId(String Id) {
        this.Id = Id;
    }
}
