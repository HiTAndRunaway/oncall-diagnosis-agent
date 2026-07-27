package org.example.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.interceptor.LogInterceptor;
import org.example.security.RateLimitInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Web MVC 配置
 * 解决中文乱码问题
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private RateLimitInterceptor rateLimitInterceptor;

    @Autowired
    private RateLimitConfig rateLimitConfig;

    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        // 添加 UTF-8 字符串转换器
        StringHttpMessageConverter stringConverter = new StringHttpMessageConverter(StandardCharsets.UTF_8);
        stringConverter.setWriteAcceptCharset(false); // 不设置 Accept-Charset
        converters.add(0, stringConverter);
        
        // 添加 Jackson JSON 转换器，确保 UTF-8 编码
        MappingJackson2HttpMessageConverter jsonConverter = new MappingJackson2HttpMessageConverter();
        jsonConverter.setDefaultCharset(StandardCharsets.UTF_8);
        converters.add(1, jsonConverter);
    }
    
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LogInterceptor())
                .addPathPatterns("/api/**");

        // 限流开关启用时才注册限流拦截器
        if (rateLimitConfig.isEnabled()) {
            registry.addInterceptor(rateLimitInterceptor)
                    .addPathPatterns("/api/**");
        }

        WebMvcConfigurer.super.addInterceptors(registry);
    }
}
