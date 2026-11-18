package com.aitest.chat.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

/** 统一捕获接口异常：写入日志，并返回可读 message 供界面提示。 */
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
		log.warn("请求被拒绝: {}", ex.getMessage(), ex);
		return ResponseEntity.badRequest().body(body(safe(ex.getMessage(), "请求参数不正确")));
	}

	@ExceptionHandler({ ServerWebInputException.class, DecodingException.class, DataBufferLimitException.class })
	public ResponseEntity<Map<String, String>> badInput(Exception ex) {
		log.warn("请求体无效", ex);
		String message = "请求无法解析。若附带了很大的文档，请改用「知识库」上传。";
		if (ex instanceof DataBufferLimitException || rootMessage(ex).toLowerCase().contains("exceeded")) {
			message = "请求体过大。请把大文档放到「知识库」上传；聊天附件请只带图片。";
		}
		return ResponseEntity.badRequest().body(body(message));
	}

	@ExceptionHandler(IllegalStateException.class)
	public ResponseEntity<Map<String, String>> conflict(IllegalStateException ex) {
		log.error("业务状态异常", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(body(safe(ex.getMessage(), "服务处理失败")));
	}

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<Map<String, String>> status(ResponseStatusException ex) {
		log.warn("HTTP 状态异常 {}: {}", ex.getStatusCode(), ex.getReason(), ex);
		HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
		if (status == null) {
			status = HttpStatus.INTERNAL_SERVER_ERROR;
		}
		return ResponseEntity.status(status).body(body(safe(ex.getReason(), status.getReasonPhrase())));
	}

	@ExceptionHandler(Throwable.class)
	public ResponseEntity<Map<String, String>> any(Throwable ex) {
		log.error("未处理异常", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(body(safe(ex.getMessage(), "服务出错了，详情已写入日志")));
	}

	private static Map<String, String> body(String message) {
		Map<String, String> map = new LinkedHashMap<>();
		map.put("message", message);
		return map;
	}

	private static String rootMessage(Throwable ex) {
		Throwable current = ex;
		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		return current.getMessage() == null ? "" : current.getMessage();
	}

	private static String safe(String message, String fallback) {
		if (message == null || message.isBlank()) {
			return fallback;
		}
		String trimmed = message.replace('\n', ' ').trim();
		return trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
	}

}
