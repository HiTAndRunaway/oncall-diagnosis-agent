package org.example.service.parser;

/**
 * 文档解析异常，表示文件无法被解析（加密、损坏、格式不支持等）
 * 继承 RuntimeException，由上层统一异常处理兜底
 */
public class DocumentParseException extends RuntimeException {

    public DocumentParseException(String message) {
        super(message);
    }

    public DocumentParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
