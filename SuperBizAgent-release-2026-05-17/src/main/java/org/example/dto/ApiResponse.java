package org.example.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 通用 API 响应包装器，用于一致的响应格式化。
 *
 * @param <T> 响应数据的类型
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private final int code;
    private final String message;
    private final T data;
    private final long timestamp;
    private String requestId;
    private String path;
    private PageInfo page;

    private ApiResponse(int code, String message, T data, long timestamp) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = timestamp;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "success", data, System.currentTimeMillis());
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null, System.currentTimeMillis());
    }

    public ApiResponse<T> withRequestId(String requestId) {
        this.requestId = requestId;
        return this;
    }

    public ApiResponse<T> withPath(String path) {
        this.path = path;
        return this;
    }

    public ApiResponse<T> withPage(int page, int size, long total) {
        this.page = new PageInfo(page, size, total);
        return this;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getPath() {
        return path;
    }

    public PageInfo getPage() {
        return page;
    }

    /**
     * 分页信息内部记录。
     */
    public record PageInfo(int page, int size, long total) {
    }
}
