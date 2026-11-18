package com.aitest.chat.api;

import java.util.List;
import java.util.Locale;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aitest.chat.desktop.deps.ExternalDependencyId;
import com.aitest.chat.desktop.deps.ExternalDependencyService;
import com.aitest.chat.desktop.deps.ExternalDependencyStatus;

/** 本机外部依赖检测与自动安装 API。 */
@RestController
@RequestMapping("/api/deps")
public class DependencyController {

	private final ExternalDependencyService dependencyService;

	/** 注入依赖生命周期服务。 */
	public DependencyController(ExternalDependencyService dependencyService) {
		this.dependencyService = dependencyService;
	}

	/** 列出全部依赖状态。 */
	@GetMapping
	public List<ExternalDependencyStatus> list() {
		return this.dependencyService.list();
	}

	/** 仅列出尚未安装且可自动安装的依赖。 */
	@GetMapping("/missing")
	public List<ExternalDependencyStatus> missing() {
		return this.dependencyService.missingInstallable();
	}

	/** 查询单个依赖状态。 */
	@GetMapping("/{id}")
	public ExternalDependencyStatus one(@PathVariable("id") String id) {
		return this.dependencyService.status(parse(id));
	}

	/** 开始自动安装指定依赖。 */
	@PostMapping("/{id}/install")
	public ExternalDependencyStatus install(@PathVariable("id") String id) {
		return this.dependencyService.startInstall(parse(id));
	}

	/** 解析路径中的依赖 id。 */
	private static ExternalDependencyId parse(String raw) {
		try {
			return ExternalDependencyId.valueOf(raw.trim().toUpperCase(Locale.ROOT));
		}
		catch (RuntimeException ex) {
			throw new IllegalArgumentException("未知依赖：" + raw);
		}
	}

}
