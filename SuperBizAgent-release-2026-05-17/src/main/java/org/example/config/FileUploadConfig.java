package org.example.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Configuration
@ConfigurationProperties(prefix = "file.upload")
public class FileUploadConfig {

    private String path;
    private String allowedExtensions;

    @Value("${spring.servlet.multipart.max-file-size:20MB}")
    private String maxFileSize;

    @Value("${resilience4j.ratelimiter.instances.file-upload.limit-for-period:10}")
    private int maxRequestsPerMinute;

    public void setPath(String path) {
        this.path = path;
    }

    public void setAllowedExtensions(String allowedExtensions) {
        this.allowedExtensions = allowedExtensions;
    }
}
