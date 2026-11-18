package com.aitest.chat.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.aitest.chat.config.OllamaDefaults;
import com.aitest.chat.knowledge.config.RagProperties;

/** Spring Boot 入口：安装模式准备后启动应用。 */
@SpringBootApplication(scanBasePackages = "com.aitest.chat")
@EnableConfigurationProperties(value = { OllamaDefaults.class, RagProperties.class })
@EnableScheduling
public class OllamaChatApplication {

	/** 初始化数据目录、未捕获异常落盘，并运行 Spring 容器。 */
	public static void main(String[] args) {
		InstalledMode.prepare();
		ensureLogDir();
		Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
			LoggerFactory.getLogger(OllamaChatApplication.class)
				.error("未捕获异常 thread={}", thread.getName(), error);
		});
		SpringApplication.run(OllamaChatApplication.class, args);
	}

	private static void ensureLogDir() {
		try {
			String file = System.getProperty("logging.file.name", "logs/app.log");
			Path path = Path.of(file).toAbsolutePath().normalize();
			Path parent = path.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
		}
		catch (Exception ex) {
			LoggerFactory.getLogger(OllamaChatApplication.class)
				.warn("无法创建日志目录: {}", ex.toString());
		}
	}

}