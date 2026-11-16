package com.aitest.chat.chat;

import java.util.Map;

import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把业务异常收成 {"message": "..."}，前端按这个字段显示错误。
 * 参数不合法是 400，保存失败等内部状态错误是 500。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	/** 配置校验、消息校验失败时，把原因原样返回。 */
	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
		return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
	}

	/** 例如配置写盘失败。消息同样放在 message 字段里。 */
	@ExceptionHandler(IllegalStateException.class)
	public ResponseEntity<Map<String, String>> conflict(IllegalStateException ex) {
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", ex.getMessage()));
	}

	/** 上传超过编解码缓冲时，给出和业务校验一样的说明。 */
	@ExceptionHandler(DataBufferLimitException.class)
	public ResponseEntity<Map<String, String>> tooLarge(DataBufferLimitException ex) {
		return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("message", "文件不能超过 20MB"));
	}

}
