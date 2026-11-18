package com.aitest.chat.conversation;

import org.springframework.web.reactive.function.client.WebClientResponseException;

/** 把 Ollama / 嵌入相关异常收成用户可读短句。 */
public final class OllamaErrorMessages {

	/** 工具类，禁止实例化。 */
	private OllamaErrorMessages() {
	}

	/** 遍历异常链，返回适合展示给用户的模型/嵌入错误文案。 */
	public static String friendly(Throwable ex) {
		Throwable current = ex;
		while (current != null) {
			String message = current.getMessage() == null ? "" : current.getMessage();
			if (message.contains("向量库") || message.contains("嵌入模型")) {
				return abbreviate(message);
			}
			current = current.getCause();
		}
		current = ex;
		while (current != null) {
			String message = current.getMessage() == null ? "" : current.getMessage();
			if (message.contains("Connection refused") || message.contains("connect timed out")
					|| message.contains("timed out")) {
				return "无法连接 Ollama。请确认服务已启动，并且地址填写正确。";
			}
			current = current.getCause();
		}
		if (ex instanceof WebClientResponseException responseException) {
			String body = responseException.getResponseBodyAsString();
			if (body != null && body.contains("not found")) {
				return "找不到这个模型。请先在 Ollama 中拉取，或从列表里重新选择。";
			}
			if (body != null && !body.isBlank()) {
				return "模型调用失败：" + abbreviate(body);
			}
		}
		String message = ex.getMessage() == null ? "模型调用失败" : ex.getMessage();
		if (message.contains("not found")) {
			return "找不到这个模型。请先在 Ollama 中拉取，或从列表里重新选择。";
		}
		return "模型调用失败：" + abbreviate(message);
	}

	/** 探测连接失败时的短说明（不含模型调用文案）。 */
	public static String connectionFriendly(Throwable ex) {
		Throwable current = ex;
		while (current != null) {
			String message = current.getMessage() == null ? "" : current.getMessage();
			if (message.contains("Connection refused") || message.contains("connect timed out")
					|| message.contains("timed out")) {
				return "无法连接 Ollama。请确认服务已启动，并且地址填写正确。";
			}
			current = current.getCause();
		}
		String message = ex.getMessage() == null ? "连接失败" : ex.getMessage();
		return abbreviate(message);
	}

	/** 去掉换行并将过长消息裁到 300 字。 */
	public static String abbreviate(String value) {
		String trimmed = value.replace('\n', ' ').trim();
		return trimmed.length() > 300 ? trimmed.substring(0, 300) : trimmed;
	}

}
