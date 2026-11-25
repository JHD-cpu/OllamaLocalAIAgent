package com.aitest.chat.settings;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.aitest.chat.config.OllamaDefaults;

import tools.jackson.databind.json.JsonMapper;

/**
 * 读写应用配置。内存里保留当前值，变更时先落盘再替换，避免写失败后内存和文件不一致。
 * 读写都走同一把锁。配置文件默认是 data/settings.json。
 */
@Service
public class SettingsService {

	private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

	private final JsonMapper jsonMapper;

	private final OllamaDefaults defaults;

	private final Path settingsFile;

	private final Object lock = new Object();

	private AppSettings current;

	public SettingsService(JsonMapper jsonMapper, OllamaDefaults defaults,
			@Value("${app.settings-file:data/settings.json}") String settingsFile) {
		this.jsonMapper = jsonMapper;
		this.defaults = defaults;
		this.settingsFile = Path.of(settingsFile);
		this.current = readOrDefault();
	}

	/** 返回当前生效的配置。 */
	public AppSettings get() {
		synchronized (this.lock) {
			return this.current;
		}
	}

	/** 返回配置文件里的默认值，并走一遍和保存时相同的校验。 */
	public AppSettings defaults() {
		return AppSettings.normalize(this.defaults.toSettings());
	}

	/**
	 * 校验后写入文件，成功才更新内存中的当前配置。
	 * 校验失败会抛 IllegalArgumentException，由接口层转成 400。
	 */
	public AppSettings update(AppSettings incoming) {
		AppSettings normalized = AppSettings.normalize(incoming);
		synchronized (this.lock) {
			persist(normalized);
			this.current = normalized;
			return this.current;
		}
	}

	/** 文件不存在或内容损坏时退回默认配置，并记下警告，不让启动失败。 */
	private AppSettings readOrDefault() {
		if (!Files.exists(this.settingsFile)) {
			return defaults();
		}
		try {
			return AppSettings.normalize(readStored());
		}
		catch (RuntimeException ex) {
			log.warn("读取 {} 失败，改用默认配置: {}", this.settingsFile, ex.getMessage());
			return defaults();
		}
	}

	/**
	 * 先按 Map 读入，再用 application.yml 里的默认值补上文件里没有的字段。
	 * 这样以前保存的配置不会因为后来新增字段而无法反序列化。
	 */
	@SuppressWarnings("unchecked")
	private AppSettings readStored() {
		Map<String, Object> defaults = this.jsonMapper.convertValue(this.defaults.toSettings(), Map.class);
		Map<String, Object> stored = this.jsonMapper.readValue(this.settingsFile.toFile(), Map.class);
		return this.jsonMapper.convertValue(overlay(defaults, stored), AppSettings.class);
	}

	/**
	 * 以默认配置为底，只覆盖文件里真正写了的同名字段。
	 * null 视为没写，文件里多出来的字段丢弃。
	 */
	static Map<String, Object> overlay(Map<String, Object> defaults, Map<String, Object> stored) {
		Map<String, Object> merged = new java.util.LinkedHashMap<>(defaults);
		if (stored == null) {
			return merged;
		}
		for (Map.Entry<String, Object> entry : stored.entrySet()) {
			if (entry.getValue() != null && merged.containsKey(entry.getKey())) {
				merged.put(entry.getKey(), entry.getValue());
			}
		}
		return merged;
	}

	/** 原子写入：先写临时文件再 replace，避免中途断电留下半截 JSON。 */
	private void persist(AppSettings settings) {
		try {
			Path parent = this.settingsFile.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Path temp = this.settingsFile.resolveSibling(this.settingsFile.getFileName() + ".tmp");
			this.jsonMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), settings);
			try {
				Files.move(temp, this.settingsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException ex) {
				Files.move(temp, this.settingsFile, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException ex) {
			throw new IllegalStateException("保存配置失败", ex);
		}
	}

}
