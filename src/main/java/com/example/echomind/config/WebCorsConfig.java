package com.example.echomind.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 放开 /api/** 的跨域限制。
 *
 * <p>正常用法是把 index.html 从 Spring Boot 的 static 目录访问（同源，其实不需要 CORS）；
 * 但如果你用 IDEA 自带的静态服务器、或者直接用浏览器打开 index.html 调试，就会跨域，
 * 所以这里统一放开，省得调试时踩坑。
 */
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
