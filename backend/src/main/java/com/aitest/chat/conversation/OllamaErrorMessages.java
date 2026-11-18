package com.aitest.chat.conversation;

import org.springframework.web.reactive.function.client.WebClientResponseException;

/** 把 Ollama / 远程模型相关异常收成用户可读短句。 */
public final class OllamaErrorMessages {

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
				return "无法连接模型服务。请确认地址正确，本机 Ollama 已启动，或远程接口可访问。";
			}
			String billing = billingHint(message);
			if (billing != null) {
				return billing;
			}
			String auth = authenticationHint(-1, message);
			if (auth != null) {
				return auth;
			}
			String media = mediaHint(message);
			if (media != null) {
				return media;
			}
			String ctx = contextHint(message);
			if (ctx != null) {
				return ctx;
			}
			current = current.getCause();
		}
		if (ex instanceof WebClientResponseException responseException) {
			String body = responseException.getResponseBodyAsString();
			String billing = billingHint(body);
			if (billing == null) {
				billing = billingHint(String.valueOf(responseException.getStatusCode().value()));
			}
			if (billing != null) {
				return billing;
			}
			String auth = authenticationHint(responseException.getStatusCode().value(), body);
			if (auth != null) {
				return auth;
			}
			String media = mediaHint(body);
			if (media != null) {
				return media;
			}
			String ctx = contextHint(body);
			if (ctx != null) {
				return ctx;
			}
			if (body != null && body.contains("not found")) {
				return "找不到这个模型。请检查模型名，或先在本机 Ollama 中拉取。";
			}
			if (body != null && !body.isBlank()) {
				return "模型调用失败：" + abbreviate(body);
			}
		}
		String message = ex.getMessage() == null ? "模型调用失败" : ex.getMessage();
		if (message.contains("not found")) {
			return "找不到这个模型。请检查模型名，或先在本机 Ollama 中拉取。";
		}
		return "模型调用失败：" + abbreviate(message);
	}

	public static String connectionFriendly(Throwable ex) {
		Throwable current = ex;
		while (current != null) {
			String message = current.getMessage() == null ? "" : current.getMessage();
			String billing = billingHint(message);
			if (billing != null) {
				return billing;
			}
			String auth = authenticationHint(-1, message);
			if (auth != null) {
				return auth;
			}
			if (message.contains("Connection refused") || message.contains("connect timed out")
					|| message.contains("timed out")) {
				return "无法连接模型服务。请确认服务已启动，并且地址填写正确。";
			}
			current = current.getCause();
		}
		String message = ex.getMessage() == null ? "连接失败" : ex.getMessage();
		return abbreviate(message);
	}

	/** 402 / 余额不足。 */
	static String billingHint(String raw) {
		String text = raw == null ? "" : raw;
		String lower = text.toLowerCase();
		if (text.contains("402") || lower.contains("insufficient balance") || lower.contains("insufficient_quota")
				|| lower.contains("exceeded your current quota") || lower.contains("billing") && lower.contains("hard limit")
				|| text.contains("余额不足") || text.contains("欠费")) {
			return "远程账户余额不足或配额已用尽（HTTP 402）。Key 可能仍然有效，所以「测试连接」会成功，但对话会失败。"
					+ " 请到对应平台充值或更换有额度的账号后再试。";
		}
		return null;
	}

	/** 上下文窗口不足。 */
	static String contextHint(String raw) {
		String text = raw == null ? "" : raw;
		String lower = text.toLowerCase();
		if (lower.contains("exceeds the available context") || lower.contains("context length")
				|| lower.contains("context_length_exceeded") || lower.contains("maximum context length")
				|| lower.contains("too many tokens") || text.contains("上下文") && text.contains("超过")) {
			return "输入超出模型上下文窗口。已尽量自动截断历史/附件；仍失败请到配置「采样」增大「上下文长度」（如 32768），"
					+ "或清空对话、减少附件后再试。";
		}
		return null;
	}

	/** 上游无法加载图片/音频（常见于文档误当 media，或模型不支持视觉）。 */
	static String mediaHint(String raw) {
		String text = raw == null ? "" : raw;
		String lower = text.toLowerCase();
		if (lower.contains("failed to load image or audio") || lower.contains("failed to download image")
				|| lower.contains("invalid image") || lower.contains("unsupported image")
				|| text.contains("无法加载图片") || text.contains("不支持的图片")) {
			return "模型无法加载图片/音频附件。请确认：1）当前模型支持视觉；2）附件是常见图片格式（png/jpg/webp）。"
					+ " 文档类附件会自动提取文本后发送，请勿依赖把 pdf/docx 直接当图片识别。";
		}
		return null;
	}

	static String authenticationHint(int status, String raw) {
		String text = raw == null ? "" : raw;
		boolean unauthorized = status == 401 || status == 403
				|| text.toLowerCase().contains("authentication")
				|| text.toLowerCase().contains("invalid_api_key")
				|| (text.toLowerCase().contains("api key") && text.toLowerCase().contains("invalid"))
				|| text.contains("Authorization Required");
		if (!unauthorized) {
			return null;
		}
		return "远程 API 鉴权失败：请检查提供方是否选对，并在配置中重新粘贴有效的 API Key 后保存。"
				+ " DeepSeek 密钥在 platform.deepseek.com 创建；豆包用火山方舟的 Key，二者不能混用。";
	}

	public static String abbreviate(String value) {
		String trimmed = value.replace('\n', ' ').trim();
		return trimmed.length() > 300 ? trimmed.substring(0, 300) : trimmed;
	}

}
