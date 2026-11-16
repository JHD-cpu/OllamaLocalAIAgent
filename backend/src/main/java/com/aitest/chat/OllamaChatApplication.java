package com.aitest.chat;

import org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.aitest.chat.config.OllamaDefaults;
import com.aitest.chat.rag.RagProperties;

/**
 * 应用入口。打开配置绑定，让 app.ollama.* 和 app.rag.* 从配置文件读进来。
 * Qdrant 自动配置会在启动时连向量库，这里关掉，改由知识库在第一次使用时再连接。
 */
@SpringBootApplication(exclude = QdrantVectorStoreAutoConfiguration.class)
@EnableConfigurationProperties({ OllamaDefaults.class, RagProperties.class })
public class OllamaChatApplication {

	public static void main(String[] args) {
		SpringApplication.run(OllamaChatApplication.class, args);
	}

}
