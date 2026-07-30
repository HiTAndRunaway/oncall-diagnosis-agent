package org.example.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.example.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器，将异常转换为带有适当HTTP状态码的标准化ApiResponse JSON响应体。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理所有业务异常。
     * 使用异常内置的httpStatus设置响应状态码。
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException ex, HttpServletRequest req) {
        log.warn("[BizException] path={} errorCode={} message={}",
                req.getRequestURI(), ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(ex.getHttpStatus())
                .body(ApiResponse.<Void>error(ex.getHttpStatus(), ex.getMessage())
                        .withRequestId(MDC.get("requestId"))
                        .withPath(req.getRequestURI()));
    }

    /**
     * 处理来自@Valid注解的校验失败。
     * 提取字段级别的错误信息并进行拼接。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest req) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        log.warn("[Validation] path={} errors={}", req.getRequestURI(), detail);
        return ResponseEntity.status(400)
                .body(ApiResponse.<Void>error(400, "参数校验失败: " + detail)
                        .withRequestId(MDC.get("requestId"))
                        .withPath(req.getRequestURI()));
    }

    /**
     * 兜底异常处理器，处理未预期的异常。
     * 返回通用消息以避免泄露内部细节。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception ex, HttpServletRequest req) {
        log.error("[Unhandled Error] path={} type={} message={}",
                req.getRequestURI(), ex.getClass().getName(), ex.getMessage(), ex);
        return ResponseEntity.status(500)
                .body(ApiResponse.<Void>error(500, "系统内部错误，请稍后重试")
                        .withRequestId(MDC.get("requestId"))
                        .withPath(req.getRequestURI()));
    }
}
