package com.aitest.chat.api;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;

import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * 兜底 WebFlux 异常（未进 ControllerAdvice 的也会写日志），返回带 message 的 JSON。
 */
@Component
@Order(-2)
public class GlobalErrorWebExceptionHandler implements WebExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalErrorWebExceptionHandler.class);

	private final JsonMapper jsonMapper;

	public GlobalErrorWebExceptionHandler(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	@Override
	public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
		if (exchange.getResponse().isCommitted()) {
			return Mono.error(ex);
		}
		log.error("Web 请求异常 path={}", exchange.getRequest().getPath(), ex);

		HttpStatus status = resolveStatus(ex);
		String message = resolveMessage(ex, status);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("message", message);
		payload.put("status", status.value());
		payload.put("path", exchange.getRequest().getPath().value());

		byte[] bytes;
		try {
			bytes = this.jsonMapper.writeValueAsBytes(payload);
		}
		catch (Exception encodeEx) {
			log.error("编码错误响应失败", encodeEx);
			bytes = ("{\"message\":\"" + escape(message) + "\"}").getBytes(StandardCharsets.UTF_8);
		}

		ServerHttpResponse response = exchange.getResponse();
		response.setStatusCode(status);
		response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
		DataBuffer buffer = response.bufferFactory().wrap(bytes);
		return response.writeWith(Mono.just(buffer));
	}

	private static HttpStatus resolveStatus(Throwable ex) {
		if (ex instanceof ResponseStatusException statusEx) {
			HttpStatus resolved = HttpStatus.resolve(statusEx.getStatusCode().value());
			return resolved == null ? HttpStatus.INTERNAL_SERVER_ERROR : resolved;
		}
		if (ex instanceof IllegalArgumentException) {
			return HttpStatus.BAD_REQUEST;
		}
		return HttpStatus.INTERNAL_SERVER_ERROR;
	}

	private static String resolveMessage(Throwable ex, HttpStatus status) {
		if (ex instanceof ResponseStatusException statusEx && statusEx.getReason() != null && !statusEx.getReason().isBlank()) {
			return clip(statusEx.getReason());
		}
		if (ex.getMessage() != null && !ex.getMessage().isBlank()) {
			return clip(ex.getMessage());
		}
		return status.is4xxClientError() ? "请求不正确" : "服务出错了，详情已写入日志";
	}

	private static String clip(String message) {
		String trimmed = message.replace('\n', ' ').trim();
		return trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
	}

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

}
