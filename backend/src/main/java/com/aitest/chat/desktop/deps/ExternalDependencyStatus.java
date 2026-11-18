package com.aitest.chat.desktop.deps;

/**
 * 单个外部依赖的检测/安装状态。
 * phase：idle / downloading / installing / ready / error。
 */
public record ExternalDependencyStatus(String id, String name, String description, boolean installed, boolean installable,
		boolean busy, String phase, String message, String path) {
}
