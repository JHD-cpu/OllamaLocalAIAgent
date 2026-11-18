package com.aitest.chat.api;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 将常见业务异常映射为 JSON 错误响应。 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 参数不合法 → 400 与 message 字段。 */
    @ExceptionHandler(value={IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        log.warn("\u8bf7\u6c42\u88ab\u62d2\u7edd: {}", (Object)ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }

    /** 状态冲突或内部失败 → 500 与 message 字段。 */
    @ExceptionHandler(value={IllegalStateException.class})
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException ex) {
        log.warn("\u8bf7\u6c42\u5931\u8d25", (Throwable)ex);
        return ResponseEntity.status((HttpStatusCode)HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", ex.getMessage()));
    }
}