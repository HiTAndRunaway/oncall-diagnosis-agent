package org.example.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Generic API response wrapper for consistent response formatting.
 *
 * @param <T> the type of the response data
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
     * Inner record for pagination information.
     */
    public record PageInfo(int page, int size, long total) {
    }
}
