package com.aitest.chat.bootstrap;

import com.aitest.chat.bootstrap.InstalledMode;
import com.aitest.chat.config.OllamaDefaults;
import com.aitest.chat.knowledge.config.RagProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Spring Boot 入口：安装模式准备后启动应用。 */
@SpringBootApplication(scanBasePackages = "com.aitest.chat")
@EnableConfigurationProperties(value={OllamaDefaults.class, RagProperties.class})
@EnableScheduling
public class OllamaChatApplication {
    /** 初始化数据目录并运行 Spring 容器。 */
    public static void main(String[] args) {
        InstalledMode.prepare();
        SpringApplication.run(OllamaChatApplication.class, (String[])args);
    }
}