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
 * Global exception handler that converts exceptions into
 * standardized ApiResponse JSON bodies with proper HTTP status codes.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handle all business exceptions.
     * Uses the exception's built-in httpStatus to set the response code.
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
     * Handle validation failures from @Valid annotations.
     * Extracts field-level error messages and joins them.
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
     * Catch-all handler for unexpected exceptions.
     * Returns a generic message to avoid leaking internal details.
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
