package com.aitest.chat.mcp;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

/**
 * 从 data/mcp-servers.json 加载简易 HTTP 工具插件。
 * 配置示例：
 * {"tools":[{"name":"weather","description":"查天气","url":"http://127.0.0.1:9090/weather"}]}
 * 调用时 POST JSON {"input":"..."}，期望响应 {"result":"..."} 或纯文本。
 */
@Component
public class McpToolBridge {

	private static final Logger log = LoggerFactory.getLogger(McpToolBridge.class);

	private final JsonMapper jsonMapper;

	private final Path configFile;

	private final RestClient restClient;

	public McpToolBridge(JsonMapper jsonMapper, @Value("${app.mcp-file:data/mcp-servers.json}") String mcpFile) {
		this.jsonMapper = jsonMapper;
		this.configFile = resolve(mcpFile);
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(3));
		factory.setReadTimeout(Duration.ofSeconds(30));
		this.restClient = RestClient.builder().requestFactory(factory).build();
	}

	/** 按配置文件动态生成 ToolCallback 列表（每次对话装配时读取，便于热更新）。 */
	@SuppressWarnings("unchecked")
	public List<ToolCallback> callbacks() {
		if (!Files.isRegularFile(this.configFile)) {
			return List.of();
		}
		try {
			Map<String, Object> root = this.jsonMapper.readValue(this.configFile.toFile(), Map.class);
			Object raw = root.get("tools");
			if (!(raw instanceof List<?> tools)) {
				return List.of();
			}
			List<ToolCallback> callbacks = new ArrayList<>();
			for (Object item : tools) {
				if (!(item instanceof Map<?, ?> map)) {
					continue;
				}
				String name = text(map, "name");
				String description = text(map, "description");
				String url = text(map, "url");
				if (name.isBlank() || url.isBlank()) {
					continue;
				}
				String desc = description.isBlank() ? ("外部工具 " + name) : description;
				String toolName = "mcp_" + name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
				callbacks.add(FunctionToolCallback.builder(toolName, (McpInput input) -> invoke(url, input == null ? null : input.input()))
					.description(desc)
					.inputType(McpInput.class)
					.build());
			}
			if (!callbacks.isEmpty()) {
				log.info("已加载 MCP/HTTP 工具 {} 个 from {}", callbacks.size(), this.configFile);
			}
			return callbacks;
		}
		catch (Exception ex) {
			log.warn("读取 MCP 配置失败 {}: {}", this.configFile, ex.getMessage());
			return List.of();
		}
	}

	private String invoke(String url, String input) {
		try {
			String body = this.jsonMapper.writeValueAsString(Map.of("input", input == null ? "" : input));
			String response = this.restClient.post()
				.uri(URI.create(url))
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.retrieve()
				.body(String.class);
			if (response == null || response.isBlank()) {
				return "工具无返回";
			}
			try {
				@SuppressWarnings("unchecked")
				Map<String, Object> map = this.jsonMapper.readValue(response, Map.class);
				Object result = map.get("result");
				if (result != null) {
					return String.valueOf(result);
				}
			}
			catch (RuntimeException ignored) {
				/* plain text */
			}
			return response;
		}
		catch (Exception ex) {
			return "外部工具调用失败：" + ex.getMessage();
		}
	}

	private static String text(Map<?, ?> map, String key) {
		Object value = map.get(key);
		return value == null ? "" : String.valueOf(value).trim();
	}

	private static Path resolve(String configured) {
		Path path = Path.of(configured != null && !configured.isBlank() ? configured.trim() : "data/mcp-servers.json");
		return path.isAbsolute() ? path.normalize() : Path.of(System.getProperty("user.dir")).resolve(path).normalize();
	}

	/** FunctionTool 入参。 */
	public record McpInput(String input) {
	}

}
