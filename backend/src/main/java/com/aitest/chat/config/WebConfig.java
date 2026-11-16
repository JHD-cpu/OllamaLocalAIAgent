package com.aitest.chat.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * 允许本机前端开发服务器跨域调用 /api。
 * 只放行 localhost 和 127.0.0.1 的任意端口，不对外网来源开放。
 */
@Configuration
public class WebConfig implements WebFluxConfigurer {

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/api/**")
			.allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
			.allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
			.allowedHeaders("*");
	}

	/** 知识库上传允许到 20MB，默认缓冲只有 256KB。 */
	@Override
	public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
		configurer.defaultCodecs().maxInMemorySize(21 * 1024 * 1024);
	}

}
