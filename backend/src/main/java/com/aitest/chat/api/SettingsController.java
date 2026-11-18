package com.aitest.chat.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aitest.chat.settings.AppSettings;
import com.aitest.chat.settings.SettingsService;

/** 应用运行时配置的读写 API。 */
@RestController
@RequestMapping("/api")
public class SettingsController {

	private final SettingsService settingsService;

	/** 注入配置服务。 */
	public SettingsController(SettingsService settingsService) {
		this.settingsService = settingsService;
	}

	/** 返回当前生效配置。 */
	@GetMapping("/settings")
	public AppSettings settings() {
		return this.settingsService.get();
	}

	/** 返回 application 默认配置（未合并本地文件）。 */
	@GetMapping("/settings/defaults")
	public AppSettings defaultSettings() {
		return this.settingsService.defaults();
	}

	/** 校验并持久化新配置，返回归一化后的结果。 */
	@PutMapping("/settings")
	public AppSettings updateSettings(@RequestBody AppSettings settings) {
		return this.settingsService.update(settings);
	}

}